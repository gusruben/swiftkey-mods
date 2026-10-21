package app.revanced.extension.swiftkey.twothumb;

/**
 * The engine's data files, shipped in the dex so the patch needs no resource changes. The patch
 * replaces {@link #file} with one that builds each file's bytes from an array payload.
 */
final class NintypeData {
    private NintypeData() {
    }

    /** The file at [index] in TwoThumbSwipe's file list, or null when the patch didn't fill it in. */
    static byte[] file(int index) {
        return null;
    }
}
