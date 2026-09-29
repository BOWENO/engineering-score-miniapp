package com.acme.performance.publicity.service;

import com.acme.performance.auth.model.CurrentUser;
import com.acme.performance.common.api.ApiException;
import com.acme.performance.settlement.service.DGradeService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Service
public class PublicBoardService {
    private final JdbcClient jdbc;
    public PublicBoardService(JdbcClient jdbc) { this.jdbc = jdbc; }

    public BoardView board(CurrentUser viewer, String period, boolean anonymous) {
        DGradeService.validatePeriod(period);
        UUID runId = jdbc.sql("""
                SELECT g.settlement_run_id FROM grade_snapshot g
                WHERE g.period=:period AND g.user_id=:userId AND g.status='PUBLISHED'
                ORDER BY g.published_at DESC LIMIT 1
                """).param("period", period).param("userId", viewer.userId()).query(UUID.class).optional()
                .orElseThrow(() -> new ApiException("PUBLIC_BOARD_NOT_PUBLISHED", "本周期结算尚未发布", HttpStatus.NOT_FOUND));
        List<BoardItem> items = jdbc.sql("""
                SELECT g.id,g.user_id,u.display_name,g.total,g.rank_no,g.grade FROM grade_snapshot g
                JOIN app_user u ON u.id=g.user_id WHERE g.settlement_run_id=:runId AND g.status='PUBLISHED' ORDER BY g.rank_no
                """).param("runId", runId).query((rs, n) -> {
                    int rank = rs.getInt("rank_no");
                    String name = anonymous ? "员工" + String.format("%03d", rank) : rs.getString("display_name");
                    boolean self = rs.getObject("user_id", UUID.class).equals(viewer.userId());
                    return new BoardItem(self, self ? rs.getObject("id", UUID.class) : null, name,
                            rs.getBigDecimal("total"), rank, rs.getString("grade"));
                }).list();
        return new BoardView(period, anonymous, items);
    }

    public record BoardView(String period, boolean anonymous, List<BoardItem> items) {}
    public record BoardItem(boolean self, UUID resultId, String displayName, BigDecimal total, int rank, String grade) {}
}
