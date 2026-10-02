package com.envi.wispr.polish

import android.os.Parcel
import android.os.Parcelable
import com.envi.wispr.cleanup.CleanupOptions
import com.envi.wispr.cleanup.EnglishSpelling

/** Immutable take options. A large legal vocabulary is never silently truncated for Binder. */
internal class CleanupRequestOptions(val options: CleanupOptions) : Parcelable {
    override fun describeContents(): Int = 0
    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeInt(if (options.removeFillers) 1 else 0)
        dest.writeInt(if (options.spokenEmoji) 1 else 0)
        dest.writeInt(if (options.spokenPunctuation) 1 else 0)
        dest.writeString(options.englishSpelling.name)
        dest.writeStringList(options.spellingProtectedWords.sorted())
    }
    companion object {
        @JvmField val CREATOR: Parcelable.Creator<CleanupRequestOptions> = object : Parcelable.Creator<CleanupRequestOptions> {
            override fun createFromParcel(source: Parcel): CleanupRequestOptions = CleanupRequestOptions(CleanupOptions(
                removeFillers = source.readInt() == 1,
                spokenEmoji = source.readInt() == 1,
                spokenPunctuation = source.readInt() == 1,
                englishSpelling = EnglishSpelling.fromStored(source.readString()),
                spellingProtectedWords = source.createStringArrayList().orEmpty().toSet(),
            ))
            override fun newArray(size: Int): Array<CleanupRequestOptions?> = arrayOfNulls(size)
        }
    }
}
