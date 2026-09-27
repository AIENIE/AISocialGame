package com.aisocialgame.dto;

import com.aisocialgame.model.GameLogEntry;
import java.util.List;

/** Chronological public log page; nextCursor is a public-only sequence. */
public record GameLogPage(List<GameLogEntry> items, Long nextCursor, boolean hasMore) {}
