# Faithful port (v2) of the macOS app's default batch recipe: FluidAudio fork b29591ad,
# AsrManager(ASRConfig(melChunkContext: true)) -> ChunkProcessor.swift (chunk loop :520-635, transcribeChunk :755-790,
# mergeChunks :952-1219, enforceMonotonic/collapseSeamWordDuplicates :820-938, repairSeamGaps :1394-1514).
import unicodedata, numpy as np
SR=16000; FR=1280; PAD=240000; CHUNK=238080; OVL=32000; STRIDE=CHUNK-OVL; FD=0.08; REPAIR_WIN=(PAD-160)//FR*FR
def punct_or_sym(s): return bool(s) and all(unicodedata.category(c)[0] in 'PS' for c in s)
def boundary(p): return p.startswith(('▁',' '))
def is_punct_piece(p):
    c=p[1:] if p.startswith('▁') else p; c=c.strip()
    return bool(c) and all(unicodedata.category(ch).startswith('P') for ch in c)
class Backend:
    def __init__(s,model,bp=0.0):
        s.A=model.asr; s.bp=bp; s.V=s.A._vocab
        s.safe={i for i,p in s.V.items() if p and (boundary(p) or punct_or_sym(p))}
        groups={}
        for i,p in s.V.items(): groups.setdefault(p.lower(),[]).append(i)
        s.canon={}
        for f,ids in groups.items():
            if len(ids)>1:
                c=next((i for i in ids if s.V[i]==f),min(ids))
                for i in ids: s.canon[i]=c
    def joint(s,hist,st,frame):
        lg,step,ns=s.A._decode(hist,st,frame); lg=lg.copy(); lg[s.A._blank_idx]-=s.bp; return int(lg.argmax()),int(step),ns
    def transcribe_chunk(s,samples,ctx,chunk_start,is_last,emit_after=None,t0_override=None):
        """ChunkProcessor.transcribeChunk: returns list of (token, global_ts, duration)."""
        A=s.A; blank=A._blank_idx; n=len(samples)
        if n==0: return []
        xp=np.pad(samples,(0,max(0,PAD-n)))
        f,l=A._preprocessor(xp[None],np.array([n])); e,el=A._encode(f,l); enc=e[0]
        actual=-(-(n-ctx)//FR); T=min(int(el[0]),actual); off=chunk_start//FR
        if T<=1: return []
        t=t0_override if t0_override is not None else ctx//FR
        st=A._create_state(); out=[]; hist=[]; last_emit=-1; at=0; cnt=0
        while t<T:
            tok,dur,ns=s.joint(hist,st,enc[t])
            if tok!=blank and dur==0 and t==last_emit and at>=1: dur=1
            if tok==blank and dur==0: dur=1
            cur=t; t+=dur
            if not t<T: break
            if tok==blank: continue
            cnt+=1
            if cnt>150: break
            g=cur+off
            if emit_after is None or g>=emit_after: out.append((tok,g,dur))
            hist.append(tok); st=ns
            at=at+1 if cur==last_emit else 1; last_emit=cur
            if at>=10: t=cur+1; at=0
        if is_last:
            blanks=0; idx=min(t,T-1); rot=[idx,T-1,max(0,T-2)]
            for k in range(10):
                if blanks>=5: break
                tok,dur,ns=s.joint(hist,st,enc[rot[k%3]])
                if tok==blank or not is_punct_piece(s.V[tok]): blanks+=1
                else:
                    g=min(idx,T-1)+off
                    if emit_after is None or g>=emit_after: out.append((tok,g,1))
                    hist.append(tok); st=ns
                idx+=max(1,dur); rot[0]=min(idx,T-1)
        return out
def speech_end(x):
    end=len(x)
    while end>0:
        fs=max(0,end-FR); seg=x[fs:end]
        if np.sqrt(np.mean(seg*seg))>=0.0005: return end
        end=fs
    return len(x)
def last_warmup(start,total,se):
    is_last=start+CHUNK>=total; rem=min(se,total)-start
    if not is_last or rem<=0 or start<=0: return 0
    fill=(CHUNK-rem)//FR*FR
    if fill<=0: return 0
    return max(0,min(start//FR*FR,fill))
def transcribe(b,x,seam_repair=True):
    n=len(x)
    if n<4800: return ''
    if n<=PAD:
        if n%FR: x=np.pad(x,(0,FR-n%FR))
        return text(b,b.transcribe_chunk(x,0,0,True))
    se=speech_end(x); outs=[]; start=0; k=0
    while start<n:
        w=last_warmup(start,n,se); vis=max(FR,CHUNK-w); cand=start+vis; is_last=cand>=n
        end=n if is_last else cand; aend=min(end,se) if is_last else end
        if end<=start or aend<=start: break
        ctx=0 if w>0 else (FR if k>0 else 0); cstart=start-max(w,ctx)
        seg=x[cstart:aend]
        emit=start//FR if w>0 else None
        outs.append(b.transcribe_chunk(seg,ctx,cstart if w>0 else start,is_last,emit_after=emit,t0_override=0 if emit is not None else None))
        k+=1
        if is_last: break
        start+=STRIDE
    outs=[o for o in outs]
    merged=outs[0]
    if len(outs)>1:
        for o in outs[1:]: merged=merge(b,merged,o)
        merged=monotonic(merged); merged=collapse(b,merged)
    else: merged=monotonic(merged)
    if len(outs)>1 and len(merged)>1 and seam_repair: merged=repair(b,x,merged)
    return text(b,merged)
def monotonic(t):
    if len(t)<2: return t
    r=list(t); lt=r[0][1]
    for i in range(1,len(r)):
        if r[i][1]<lt: r[i]=(r[i][0],lt,r[i][2])
        else: lt=r[i][1]
    return r
def collapse(b,tokens):
    V=b.V; of=int(round(2.0/FD)); words=[]
    for tk in tokens:
        if not words or boundary(V[tk[0]]): words.append([tk])
        else: words[-1].append(tk)
    info=[]
    for w in words:
        t=''.join(V[x[0]][1:] if V[x[0]].startswith('▁') else V[x[0]] for x in w)
        core=t.strip().strip(''.join(c for c in t if unicodedata.category(c).startswith('P')) or ' ')
        core=t.strip(' '+''.join(ch for ch in set(t) if unicodedata.category(ch).startswith('P')))
        info.append((core, w[0][1], bool(t) and t[-1] in '.?!:'))
    keep=[True]*len(words); lk=-1
    for i in range(len(words)):
        if lk<0: lk=i; continue
        pc,ps,pe=info[lk]; cc,cs,_=info[i]
        dup= pc and cc and pc!=cc and pc.lower()==cc.lower() and cc[0].isalpha() and not pe and cs-ps<=of
        if not dup: lk=i; continue
        if cc==cc.lower() and pc!=pc.lower(): keep[lk]=False; lk=i
        else: keep[i]=False
    return [tk for w,k in zip(words,keep) if k for tk in w]
def merge(b,left,right):
    if not left: return right
    if not right: return left
    le=left[-1][1]*FD+FD; rs=right[0][1]*FD
    if le<=rs: return left+right
    OL=[(i,t) for i,t in enumerate(left) if t[1]*FD+FD>rs-2.0]
    OR=[(j,t) for j,t in enumerate(right) if t[1]*FD<le+2.0]
    if len(OL)<2 or len(OR)<2: return midpoint(b,left,right,le,rs)
    def idm(a,c): return a==c or (a in b.canon and c in b.canon and b.canon[a]==b.canon[c])
    m=lambda p,q: idm(OL[p][1][0],OR[q][1][0]) and abs(OL[p][1][1]*FD-OR[q][1][1]*FD)<1.0
    best=[]
    for p in range(len(OL)):
        for q in range(len(OR)):
            if m(p,q):
                cur=[]; kk,ll=p,q
                while kk<len(OL) and ll<len(OR) and m(kk,ll): cur.append((kk,ll)); kk+=1; ll+=1
                if len(cur)>len(best): best=cur
    if len(best)>=max(len(OL)//2,1): return use_matches(b,best,OL,OR,left,right)
    L,R=len(OL),len(OR); dp=[[0]*(R+1) for _ in range(L+1)]
    for i in range(1,L+1):
        for j in range(1,R+1): dp[i][j]=dp[i-1][j-1]+1 if m(i-1,j-1) else max(dp[i-1][j],dp[i][j-1])
    i,j,lcs=L,R,[]
    while i>0 and j>0:
        if m(i-1,j-1): lcs.append((i-1,j-1)); i-=1; j-=1
        elif dp[i-1][j]>dp[i][j-1]: i-=1
        else: j-=1
    lcs.reverse()
    if not lcs: return midpoint(b,left,right,le,rs)
    return use_matches(b,lcs,OL,OR,left,right)
def use_matches(b,matches,OL,OR,left,right):
    safe=b.safe; li=[OL[p][0] for p,_ in matches]; ri=[OR[q][0] for _,q in matches]; res=[]
    if li[0]>0: res+=left[:li[0]]
    for x in range(len(matches)):
        res.append(left[li[x]])
        if x<len(matches)-1:
            gl=left[li[x]+1:li[x+1]] if li[x+1]>li[x]+1 else []
            gr=right[ri[x]+1:ri[x+1]] if ri[x+1]>ri[x]+1 else []
            res+= gr if len(gr)>len(gl) else gl
    lr=ri[-1]
    if lr+1<len(right):
        tail=right[lr+1:]
        if tail[0][0] not in safe:
            ws=next((q for q in range(lr,-1,-1) if right[q][0] in safe),None)
            pop=next((q for q in range(len(res)-1,-1,-1) if res[q][0] in safe),None)
            if ws is not None and pop is not None:
                res=res[:pop]; res+=right[ws:]
            else:
                c=li[-1]+1
                while c<len(left) and left[c][0] not in safe: res.append(left[c]); c+=1
                rz=next((q for q,t in enumerate(tail) if t[0] in safe),None)
                res+= tail[rz:] if rz is not None else tail
        else: res+=tail
    return res
def midpoint(b,left,right,le,rs):
    cut=(le+rs)/2; safe=b.safe
    lend=next((i for i,t in enumerate(left) if t[1]*FD>=cut),len(left))
    rst=next((i for i,t in enumerate(right) if t[1]*FD>=cut),len(right))
    if lend>0:
        while lend<len(left) and left[lend][0] not in safe: lend+=1
    sc=rst
    while sc<len(right) and right[sc][0] not in safe: sc+=1
    if sc<len(right): rst=sc
    return left[:lend]+right[rst:]
def repair(b,x,tokens):
    V=b.V; n=len(x); nf=n//FR
    fr=x[:nf*FR].reshape(nf,FR); rms=np.sqrt((fr*fr).mean(1)); nz=np.sort(rms[rms>0])
    thr=0.008 if len(nz)==0 else float(min(0.008,max(0.0005,nz[min(len(nz)-1,int(len(nz)*0.75))]*0.3)))
    def speech_s(a,c): 
        f0,f1=a//FR,-(-c//FR); return float((rms[f0:f1]>=thr).sum())*FD
    punct_only=lambda t: punct_or_sym(V.get(t,''))
    def neighbor(st,i,step):
        while 0<=i+step<len(st) and punct_only(st[i][0]): i+=step
        return st[i]
    def same(a,c): return a==c or V[a].lower()==V[c].lower()
    work=list(tokens); probes=0; probed=set(); minf=max(2,int(1.5/FD))
    for _ in range(3):
        ins=[]
        for i in range(len(work)-1):
            if probes>=32: break
            cur,nx=work[i],work[i+1]; g0=cur[1]+max(1,cur[2]); g1=nx[1]
            if g1-g0<minf or g0 in probed: continue
            s0=g0*FR; s1=min(g1*FR,n)
            if s1<=s0 or speech_s(s0,s1)<0.5: continue
            probed.add(g0); probes+=1; ctr=(s0+s1)//2; rec=[]
            for pl in (s0, ctr-REPAIR_WIN//2):
                ws=max(0,min(pl,n-REPAIR_WIN))//FR*FR; we=min(ws+REPAIR_WIN,n)
                if we<=ws: continue
                win=b.transcribe_chunk(x[ws:we],0,ws,we>=n)
                cand=[t for t in win if g0<t[1]<g1-1]
                while cand and cand[0][0] not in b.safe: cand.pop(0)
                lead=neighbor(work,i,-1); tail=neighbor(work,i+1,1)
                while cand and same(cand[0][0],lead[0]) and abs(cand[0][1]-lead[1])<=6: cand.pop(0)
                while cand and same(cand[-1][0],tail[0]) and abs(tail[1]-cand[-1][1])<=6: cand.pop()
                while cand and (cand[0][0] not in b.safe or punct_only(cand[0][0])): cand.pop(0)
                if cand: rec=cand; break
            ins+=rec
        if not ins: break
        work=sorted(work+ins,key=lambda t:t[1])
    return work
def text(b,toks): return ''.join(b.V[t[0]] for t in toks).replace('▁',' ').strip()
