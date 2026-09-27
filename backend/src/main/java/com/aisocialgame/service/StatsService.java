package com.aisocialgame.service;

import com.aisocialgame.model.GamePlayerState;
import com.aisocialgame.model.PlayerStats;
import com.aisocialgame.model.User;
import com.aisocialgame.repository.PlayerStatsRepository;
import com.aisocialgame.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Service
@Transactional
public class StatsService {
    private final PlayerStatsRepository playerStatsRepository;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;

    public StatsService(PlayerStatsRepository playerStatsRepository, org.springframework.jdbc.core.JdbcTemplate jdbc) {
        this.playerStatsRepository = playerStatsRepository;
        this.jdbc = jdbc;
    }

    public void recordResult(String archiveId, String gameId, List<GamePlayerState> players, Set<String> winnerIds) {
        if (archiveId == null || archiveId.isBlank() || "null".equals(archiveId)) throw new IllegalArgumentException("Settlement requires an archive ID");
        // Historical archives have already been settled; absence of new receipts is not permission to reward again.
        if (jdbc.queryForObject("SELECT COUNT(*) FROM game_archives WHERE id=?", Long.class, archiveId) > 0) return;
        var humans = new java.util.TreeMap<String, GamePlayerState>();
        for (var player : players) if (!player.isAi() && player.getPlayerId() != null) humans.putIfAbsent(player.getPlayerId(), player);
        for (var entry : humans.entrySet()) {
            String playerId = entry.getKey();
            jdbc.update("INSERT INTO player_settlement_locks(player_id) VALUES(?) ON DUPLICATE KEY UPDATE player_id=VALUES(player_id)", playerId);
            jdbc.queryForObject("SELECT player_id FROM player_settlement_locks WHERE player_id=? FOR UPDATE", String.class, playerId);
            // Locking/current read avoids an old REPEATABLE READ snapshot during retries.
            if (!jdbc.queryForList("SELECT player_id FROM player_settlements WHERE archive_id=? AND player_id=? FOR UPDATE", String.class, archiveId, playerId).isEmpty()) continue;
            boolean won = winnerIds.contains(playerId);
            int score = won ? 15 : 5;
            int coins = won ? 30 : 8;
            jdbc.update("INSERT INTO player_settlements(archive_id,player_id,game_id,won,score_delta,coin_delta,created_at) VALUES(?,?,?,?,?,?,CURRENT_TIMESTAMP)", archiveId, playerId, gameId, won, score, coins);
            increment(gameId, entry.getValue(), won, score);
            increment("total", entry.getValue(), won, score);
            jdbc.update("UPDATE users SET coins=coins+? WHERE id=?", coins, playerId);
        }
    }

    public List<PlayerStats> top(String gameId) {
        return playerStatsRepository.findTop20ByGameIdOrderByScoreDesc(gameId);
    }

    private void increment(String gameId, GamePlayerState player, boolean won, int score) {
        jdbc.update("""
                INSERT INTO player_stats(id,player_id,game_id,display_name,avatar,games_played,wins,score,created_at,updated_at)
                VALUES(?,?,?,?,?,1,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                ON DUPLICATE KEY UPDATE display_name=VALUES(display_name),avatar=VALUES(avatar),
                games_played=games_played+1,wins=wins+VALUES(wins),score=score+VALUES(score),updated_at=CURRENT_TIMESTAMP
                """, player.getPlayerId()+":"+gameId, player.getPlayerId(), gameId, player.getDisplayName(), player.getAvatar(), won?1:0, score);
    }
}
