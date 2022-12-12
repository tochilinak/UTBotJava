package org.utbot.fuzzing.samples;

import java.util.HashSet;
import java.util.Set;

@SuppressWarnings({"unused", "ForLoopReplaceableByForEach"})
public class Arrays {

    // should find identity matrix
    public boolean isIdentityMatrix(int[][] matrix) {
        if (matrix.length < 3) {
            throw new IllegalArgumentException("matrix.length < 3");
        }

        for (int i = 0; i < matrix.length; i++) {
            if (matrix[i].length != matrix.length) {
                return false;
            }
            for (int j = 0; j < matrix[i].length; j++) {
                if (i == j && matrix[i][j] != 1) {
                    return false;
                }

                if (i != j && matrix[i][j] != 0) {
                    return false;
                }
            }
        }

        return true;
    }

    // should fail with OOME and should reveal some branches
    public boolean isIdentityMatrix(int[][][] matrix) {
        if (matrix.length < 3) {
            throw new IllegalArgumentException("matrix.length < 3");
        }

        for (int i = 0; i < matrix.length; i++) {
            if (matrix[i].length != matrix.length) {
                return false;
            } else {
                for (int j = 0; j < matrix.length; j++) {
                    if (matrix[i][j].length != matrix[i].length) {
                        return false;
                    }
                }
            }
            for (int j = 0; j < matrix[i].length; j++) {
                for (int k = 0; k < matrix[i][j].length; k++) {
                    if (i == j && j == k && matrix[i][j][k] != 1) {
                        return false;
                    }

                    if ((i != j || j != k) && matrix[i][j][k] != 0) {
                        return false;
                    }
                }
            }
        }

        return true;
    }

    public static class Point {
        int x, y;

        public Point(int x, int y) {
            this.x = x;
            this.y = y;
        }

        boolean equals(Point other) {
            return (other.x == this.x && other.y == this.y);
        }
    }

    boolean checkAllSame(Integer[] a) {    // Fuzzer should find parameters giving true as well as parameters giving false
        if (a.length < 1) return true;
        Set<Integer> s = new HashSet<>(java.util.Arrays.asList(a));
        return (s.size() <= 1);
    }

    public boolean checkAllSamePoints(Point[] a) { // Also works for classes
        if (a.length == 4) {
            return false; // Test with array of size 4 should be generated by fuzzer
        }
        for (int i = 1; i < a.length; i++) {
            if (!a[i].equals(a[i - 1]))
                return false;
        }
        return true;
    }

    public boolean checkRowsWithAllSame2D(int[][] a, int y) {
        int cntSame = 0;
        for (int i = 0; i < a.length; i++) {
            boolean same = true;
            for (int j = 1; j < a[i].length; j++) {
                if (a[i][j] != a[i][j - 1]) {
                    same = false;
                    break;
                }
            }
            if (same)
                cntSame++;
        }
        return (cntSame == y && y > 0 && y < a.length);
    }
}
