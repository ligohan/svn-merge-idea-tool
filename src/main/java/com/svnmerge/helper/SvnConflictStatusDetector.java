package com.svnmerge.helper;

/**
 * 统一解析 svn merge、svn update、svn status 的状态列，避免组合状态漏判冲突。
 */
final class SvnConflictStatusDetector {

    private SvnConflictStatusDetector() {
    }

    static boolean hasConflictMarker(String line) {
        if (line == null || line.isBlank()) {
            return false;
        }
        return hasUpdateLikeConflict(line) || hasStatusConflict(line);
    }

    private static boolean hasUpdateLikeConflict(String line) {
        // svn merge / update 的输出使用前 4 列表示文件、属性、锁和树冲突状态，第 5 列是分隔空白。
        return hasConflictAtColumns(line, 4, 0, 1, 3);
    }

    private static boolean hasStatusConflict(String line) {
        // svn status 的输出使用前 7 列表示状态，第 8 列是分隔空白。
        return hasConflictAtColumns(line, 7, 0, 1, 6);
    }

    private static boolean hasConflictAtColumns(String line, int separatorColumn, int... conflictColumns) {
        if (line.length() <= separatorColumn || !Character.isWhitespace(line.charAt(separatorColumn))) {
            return false;
        }
        for (int column : conflictColumns) {
            if (column < line.length() && line.charAt(column) == 'C') {
                return true;
            }
        }
        return false;
    }
}
