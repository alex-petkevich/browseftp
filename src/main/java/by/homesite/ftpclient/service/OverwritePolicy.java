package by.homesite.ftpclient.service;

/**
 * Action to take when a transfer (copy / move / upload / download) hits a file
 * that already exists at the destination.
 */
public enum OverwritePolicy {
    /** Overwrite the destination file (default — preserves the previous behaviour). */
    REPLACE,
    /** Append " 1", " 2", ... before the extension until the new name is unique. */
    RENAME,
    /** Continue a partial transfer: append from the destination's current size.
     *  Implemented for FTP downloads (via the REST command) and falls back to
     *  REPLACE for any other combination. */
    RESUME,
    /** Leave the existing destination untouched and continue with the rest. */
    SKIP;

    /** Lenient parse: unknown / null values default to REPLACE. */
    public static OverwritePolicy parse(String s) {
        if (s == null) {
            return REPLACE;
        }
        try {
            return OverwritePolicy.valueOf(s.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return REPLACE;
        }
    }
}

