package by.homesite.ftpclient.service;

/**
 * Description of a name collision discovered by a preflight check before a
 * copy / move / upload / download starts. Sent to the UI so the user can
 * choose an {@link OverwritePolicy}.
 */
public record Conflict(
        /** Last segment of the path (the file or folder name). */
        String name,
        /** Source path (display). */
        String srcPath,
        /** Destination path that already exists. */
        String dstPath,
        /** Source size in bytes. -1 if unknown / a directory. */
        long srcSize,
        /** Destination size in bytes. -1 if unknown / a directory. */
        long dstSize,
        /** True if the conflict is between two directories. */
        boolean directory
) {}

