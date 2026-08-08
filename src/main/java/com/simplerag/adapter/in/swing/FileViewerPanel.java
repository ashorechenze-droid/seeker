package com.simplerag.adapter.in.swing;

import com.simplerag.application.dto.FileContentView;
import com.simplerag.application.dto.FileIndexState;
import com.simplerag.application.dto.FileNodeView;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Insets;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

/**
 * File page: the extracted text a file contributes to the index, next to the exact state the tree
 * reports for it.
 *
 * <p>The body comes from the same readers that build the index, so a PDF, DOCX or XLSX shows what
 * retrieval can actually cite instead of raw bytes.
 */
public final class FileViewerPanel extends JPanel {
    private final JLabel title = new JLabel("选择一个文件");
    private final JLabel location = new JLabel(" ");
    private final JLabel state = new JLabel(" ");
    private final JLabel meta = new JLabel(" ");
    private final JTextArea body = new JTextArea();
    private final JTextArea gutter = new JTextArea();
    private final JButton open = new JButton("打开");
    private final JButton reveal = new JButton("目录");
    private final JButton copy = new JButton("复制路径");
    private FileNodeView current;

    public FileViewerPanel(Consumer<Path> onOpenWithSystem, Consumer<Path> onReveal,
                           Consumer<Path> onCopyPath) {
        super(new BorderLayout(0, 12));
        Theme.opaque(this, Theme.PANEL_ALT);
        setBorder(Theme.padding(15, 16, 14, 16));
        add(buildHeader(), BorderLayout.NORTH);
        add(buildBody(), BorderLayout.CENTER);
        open.addActionListener(event -> withCurrent(onOpenWithSystem, false));
        reveal.addActionListener(event -> withCurrent(onReveal, true));
        copy.addActionListener(event -> withCurrent(onCopyPath, false));
        empty();
    }

    public FileNodeView selected() { return current; }

    public void empty() {
        current = null;
        buttons(false);
        title.setText("选择一个文件");
        title.setToolTipText(null);
        location.setText("在左侧资源管理器中双击文件即可在此查看正文与索引状态");
        state.setText(" ");
        state.setIcon(null);
        meta.setText(" ");
        setBody("", List.of());
    }

    public void loading(FileNodeView node) {
        header(node);
        setBody("正在读取文件内容…", List.of());
    }

    public void show(FileNodeView node, FileContentView content) {
        header(node);
        if (content.text().isEmpty()) {
            setBody(content.notice().isEmpty() ? "该文件没有可显示的文本" : content.notice(), List.of());
            return;
        }
        setBody(content.text(), content.lineLabels());
        if (!content.notice().isEmpty()) meta.setText(meta.getText() + "  ·  " + content.notice());
    }

    public void failed(FileNodeView node, String message) {
        header(node);
        setBody("无法读取该文件：" + message, List.of());
    }

    public void folder(FileNodeView node) {
        header(node);
        setBody(node.state() == FileIndexState.IGNORED
                ? "该目录按忽略或安全策略被排除，其中的文件不会进入索引。"
                : node.indexedDescendants() + " 个已索引文件（含子目录）\n\n"
                        + "在左侧展开该目录，可以逐个查看其中文件的索引状态。", List.of());
    }

    private void header(FileNodeView node) {
        current = node;
        buttons(true);
        title.setText(node.name());
        title.setToolTipText(node.path().toString());
        location.setText(node.path().toString());
        state.setIcon(FileStatusStyle.icon(node));
        state.setForeground(FileStatusStyle.color(node.state()));
        String label = FileStatusStyle.label(node.state());
        String explanation = FileStatusStyle.explanation(node);
        state.setText(label.isEmpty() ? explanation : label + "  ·  " + explanation);
        meta.setText(metaLine(node));
    }

    private static String metaLine(FileNodeView node) {
        StringBuilder line = new StringBuilder();
        if (node.directory()) {
            return line.append("目录  ·  修改于 ").append(FileStatusStyle.timestamp(node.modifiedAt()))
                    .append("  ·  ").append(node.indexedDescendants()).append(" 个已索引文件").toString();
        }
        line.append(FileStatusStyle.size(node.size()))
                .append("  ·  修改于 ").append(FileStatusStyle.timestamp(node.modifiedAt()));
        if (!node.readerId().isEmpty()) line.append("  ·  reader ").append(node.readerId());
        if (node.state() == FileIndexState.INDEXED || node.state() == FileIndexState.MODIFIED
                || node.state() == FileIndexState.DELETED) {
            line.append("  ·  ").append(node.chunkCount()).append(" 个片段");
        }
        if (!node.contentHash().isBlank()) {
            String hash = node.contentHash();
            line.append("  ·  sha ").append(hash.length() > 10 ? hash.substring(0, 10) : hash);
        }
        return line.toString();
    }

    private void setBody(String text, List<String> labels) {
        body.setText(text);
        body.setCaretPosition(0);
        int width = labels.stream().mapToInt(String::length).max().orElse(0);
        StringBuilder numbers = new StringBuilder();
        for (String label : labels) {
            numbers.append(" ".repeat(Math.max(0, width - label.length()))).append(label).append('\n');
        }
        gutter.setText(numbers.toString());
        gutter.setColumns(Math.max(2, width));
        gutter.setCaretPosition(0);
    }

    private void buttons(boolean enabled) {
        open.setEnabled(enabled);
        reveal.setEnabled(enabled);
        copy.setEnabled(enabled);
    }

    private void withCurrent(Consumer<Path> action, boolean parent) {
        if (current == null) return;
        Path path = parent && !current.directory() ? current.path().getParent() : current.path();
        if (path != null) action.accept(path);
    }

    private JPanel buildHeader() {
        JPanel header = new JPanel(new BorderLayout(12, 4));
        header.setOpaque(false);
        JPanel labels = new JPanel();
        labels.setOpaque(false);
        labels.setLayout(new BoxLayout(labels, BoxLayout.Y_AXIS));
        title.setForeground(Theme.TEXT);
        title.setFont(Theme.UI_FONT.deriveFont(Font.BOLD, 15f));
        location.setForeground(Theme.MUTED);
        location.setFont(Theme.UI_FONT.deriveFont(10f));
        state.setFont(Theme.UI_FONT.deriveFont(Font.BOLD, 11f));
        state.setIconTextGap(6);
        meta.setForeground(Theme.MUTED);
        meta.setFont(Theme.UI_FONT.deriveFont(10f));
        labels.add(title);
        labels.add(Box.createVerticalStrut(3));
        labels.add(location);
        labels.add(Box.createVerticalStrut(6));
        labels.add(state);
        labels.add(Box.createVerticalStrut(3));
        labels.add(meta);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 5, 0));
        actions.setOpaque(false);
        for (JButton button : List.of(copy, reveal, open)) {
            Theme.styleButton(button, button == open);
            button.setMargin(new Insets(6, 10, 6, 10));
            actions.add(button);
        }
        open.setToolTipText("用系统默认程序打开该文件");
        reveal.setToolTipText("在文件资源管理器中打开所在目录");
        header.add(labels, BorderLayout.CENTER);
        header.add(actions, BorderLayout.EAST);
        return header;
    }

    private JScrollPane buildBody() {
        body.setEditable(false);
        body.setFont(Theme.MONO_FONT);
        body.setBackground(Theme.PANEL_ALT);
        body.setForeground(new Color(218, 226, 230));
        body.setCaretColor(Theme.ACCENT);
        body.setTabSize(4);
        body.setBorder(Theme.padding(10, 10, 10, 10));
        gutter.setEditable(false);
        gutter.setFont(Theme.MONO_FONT);
        gutter.setBackground(Theme.PANEL);
        gutter.setForeground(new Color(104, 116, 124));
        gutter.setBorder(Theme.padding(10, 8, 10, 8));
        gutter.setFocusable(false);
        JScrollPane scroll = new JScrollPane(body);
        scroll.setRowHeaderView(gutter);
        scroll.getViewport().setBackground(Theme.PANEL_ALT);
        scroll.setBorder(BorderFactory.createLineBorder(Theme.BORDER));
        return scroll;
    }
}
