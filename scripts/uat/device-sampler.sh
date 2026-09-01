#!/system/bin/sh
# Runs ON THE PHONE (pushed to /data/local/tmp by residency-campaign.sh, started with nohup). Two loops:
# one writes MemAvailable and the memory-pressure averages every 100 ms; the other writes the PSS of the
# EnviousWispr processes as fast as dumpsys answers (about three times a second for two live processes).
# Both stop when the stop file appears; adb pulls the two CSVs afterwards.
#   device-sampler.sh <out-prefix> <stopfile>     → <out-prefix>.mem.csv and <out-prefix>.pss.csv
OUT="$1"; STOP="$2"
echo "epoch_ms,memavail_kb,psi_some_avg10,psi_full_avg10" > "$OUT.mem.csv"
echo "epoch_ms,main_pss_kb,asr_pss_kb,polish_pss_kb,audio_pss_kb" > "$OUT.pss.csv"
pss() { p=$(pidof "$1" 2>/dev/null | cut -d' ' -f1); if [ -n "$p" ]; then dumpsys meminfo -s "$p" 2>/dev/null | awk '/TOTAL PSS:/{print $3; exit}'; else echo 0; fi; }
(
  while [ ! -f "$STOP" ]; do
    echo "$(date +%s%3N),$(pss com.envi.wispr),$(pss com.envi.wispr:asr),$(pss com.envi.wispr:polish),$(pss com.envi.wispr:audio)" >> "$OUT.pss.csv"
  done
) &
while [ ! -f "$STOP" ]; do
  now=$(date +%s%3N)
  avail=$(awk '/MemAvailable/{print $2}' /proc/meminfo)
  some=$(awk '/^some/{split($2,a,"=");print a[2]}' /proc/pressure/memory 2>/dev/null); full=$(awk '/^full/{split($2,a,"=");print a[2]}' /proc/pressure/memory 2>/dev/null)
  echo "$now,$avail,${some:-0},${full:-0}" >> "$OUT.mem.csv"
  sleep 0.1
done
wait
