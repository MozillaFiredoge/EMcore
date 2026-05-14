package com.firedoge.emcore.internal.circuit;

final class DisjointSet {
    private final int[] parent;

    DisjointSet(int size) {
        parent = new int[size];
        for (int index = 0; index < size; index++) {
            parent[index] = index;
        }
    }

    int find(int value) {
        int root = value;
        while (parent[root] != root) {
            root = parent[root];
        }

        while (parent[value] != value) {
            int next = parent[value];
            parent[value] = root;
            value = next;
        }

        return root;
    }

    void union(int first, int second) {
        int firstRoot = find(first);
        int secondRoot = find(second);

        if (firstRoot != secondRoot) {
            parent[secondRoot] = firstRoot;
        }
    }
}
