package androidx.activity.result;

/** Compile-only stand-in (androidx.activity): the two accessors the companion plugin reads. */
public final class ActivityResult {
    public int getResultCode() { return 0; }
    public android.content.Intent getData() { return null; }
}
