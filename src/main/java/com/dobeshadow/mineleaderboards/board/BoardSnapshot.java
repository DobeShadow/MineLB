package com.dobeshadow.mineleaderboards.board;

import java.util.List;

/**
 * 单个榜单的采集结果快照。
 */
public record BoardSnapshot(String key, List<Entry> entries) {

    public record Entry(String name, String uuid, double value) {
    }

    public static BoardSnapshot empty(String key) {
        return new BoardSnapshot(key, List.of());
    }
}
