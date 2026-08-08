package com.simplerag.adapter.in.swing;

import com.simplerag.application.dto.FileIndexState;
import com.simplerag.application.dto.FileNodeView;

import javax.swing.Icon;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/** Single place that turns an index state into desktop wording, color and glyph. */
final class FileStatusStyle {
    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private FileStatusStyle() {
    }

    static String label(FileIndexState state) {
        return switch (state) {
            case INDEXED -> "已索引";
            case MODIFIED -> "已修改";
            case NOT_INDEXED -> "未索引";
            case DELETED -> "已删除";
            case OVERSIZED -> "超出大小限制";
            case UNSUPPORTED -> "不支持";
            case IGNORED -> "已忽略";
            case FOLDER -> "";
        };
    }

    static Color color(FileIndexState state) {
        return switch (state) {
            case INDEXED -> Theme.ACCENT;
            case MODIFIED, OVERSIZED -> Theme.AMBER;
            case DELETED -> Theme.RED;
            case UNSUPPORTED, IGNORED -> Theme.MUTED;
            case NOT_INDEXED, FOLDER -> Theme.TEXT;
        };
    }

    /** Why a file carries this state, phrased so the next action is obvious. */
    static String explanation(FileNodeView node) {
        return switch (node.state()) {
            case INDEXED -> "内容已进入当前索引，可被检索和问答引用";
            case MODIFIED -> "磁盘上的内容比索引记录新，重建索引后才会生效";
            case NOT_INDEXED -> "格式受支持但尚未进入索引，重建索引即可收录";
            case DELETED -> "索引中仍保留该文件的片段，但磁盘上已不存在，重建索引后会移除";
            case OVERSIZED -> "超过 " + node.readerId() + " reader 的大小限制，扫描时会跳过";
            case UNSUPPORTED -> "没有可处理该格式的 reader，扫描时会跳过";
            case IGNORED -> "命中忽略目录、符号链接或凭据文件策略，不会被读取";
            case FOLDER -> node.indexedDescendants() + " 个已索引文件";
        };
    }

    static String size(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024));
    }

    static String timestamp(long epochMillis) {
        return epochMillis <= 0 ? "未知" : TIMESTAMP.format(Instant.ofEpochMilli(epochMillis));
    }

    static String hex(Color color) {
        return String.format("#%02x%02x%02x", color.getRed(), color.getGreen(), color.getBlue());
    }

    static Icon icon(FileNodeView node) {
        return new StateIcon(node.directory(), node.state());
    }

    static Icon dot(FileIndexState state) {
        return new StateIcon(false, state);
    }

    /**
     * Painted rather than font-based: emoji and glyph coverage varies per Windows install, and a
     * missing glyph would turn the whole status column into boxes.
     */
    private record StateIcon(boolean directory, FileIndexState state) implements Icon {
        private static final int SIZE = 12;

        @Override public int getIconWidth() { return SIZE + 4; }
        @Override public int getIconHeight() { return SIZE; }

        @Override
        public void paintIcon(Component component, Graphics graphics, int x, int y) {
            Graphics2D canvas = (Graphics2D) graphics.create();
            canvas.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            canvas.setColor(color(state));
            if (directory) {
                canvas.drawRect(x, y + 2, SIZE - 2, SIZE - 5);
                canvas.drawLine(x, y + 1, x + 4, y + 1);
            } else {
                switch (state) {
                    case INDEXED -> canvas.fillOval(x + 1, y + 2, SIZE - 4, SIZE - 4);
                    case MODIFIED, OVERSIZED -> {
                        canvas.fillArc(x + 1, y + 2, SIZE - 4, SIZE - 4, 90, 180);
                        canvas.drawOval(x + 1, y + 2, SIZE - 4, SIZE - 4);
                    }
                    case DELETED -> {
                        canvas.drawLine(x + 2, y + 3, x + SIZE - 4, y + SIZE - 3);
                        canvas.drawLine(x + SIZE - 4, y + 3, x + 2, y + SIZE - 3);
                    }
                    case UNSUPPORTED, IGNORED -> canvas.fillOval(x + 4, y + 5, 3, 3);
                    default -> canvas.drawOval(x + 1, y + 2, SIZE - 4, SIZE - 4);
                }
            }
            canvas.dispose();
        }
    }
}
