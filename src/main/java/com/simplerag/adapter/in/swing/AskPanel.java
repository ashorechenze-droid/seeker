package com.simplerag.adapter.in.swing;

import com.simplerag.application.conversation.ChatMessage;
import com.simplerag.application.dto.CitationView;
import com.simplerag.rag.ApiConfig;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Knowledge-question page: multi-turn chat transcript + composer + citations sidebar. */
public final class AskPanel extends JPanel {
    private static final int BUBBLE_SIDE_GAP = 12;
    private static final int BUBBLE_INNER_PAD_X = 16;
    private static final int BUBBLE_INNER_PAD_Y = 12;
    private static final int USER_MAX_WIDTH_RATIO = 78;
    private static final int ASSISTANT_MAX_WIDTH_RATIO = 96;

    private final JTextField apiUrl = new JTextField();
    private final JPasswordField apiKey = new JPasswordField();
    private final JComboBox<String> apiModel = new JComboBox<>();
    private final JLabel apiStatus = new JLabel("API 未连接");
    private final JCheckBox localOnly = new JCheckBox("仅本地 RAG（禁止远程发送）");
    private final JTextArea question = new JTextArea(3, 30);
    private final JLabel conversationTitle = new JLabel("对话");
    private final JLabel conversationMeta = new JLabel("多轮上下文已启用 · 切换知识库或版本会自动清空");
    private final JButton clearChat = new JButton("清空对话");
    private final JButton ask = new JButton("发送");
    private final DefaultListModel<CitationView> citations = new DefaultListModel<>();
    private final JList<CitationView> citationList = new JList<>(citations);
    private final JPanel transcript = new JPanel();
    private final JScrollPane transcriptScroll;
    private final JPanel emptyState;
    private BubblePanel streamingAssistant;
    private final List<BubblePanel> bubbles = new ArrayList<>();

    public AskPanel(Runnable onAsk, Runnable onSave, Consumer<JButton> onFetch, Runnable onOpenCitation,
                    Runnable onClearChat) {
        super(new BorderLayout());
        Theme.opaque(this, Theme.BACKGROUND);
        transcript.setLayout(new BoxLayout(transcript, BoxLayout.Y_AXIS));
        Theme.opaque(transcript, Theme.BACKGROUND);
        transcript.setBorder(Theme.padding(12, 16, 20, 16));
        emptyState = buildEmptyState();
        transcript.add(emptyState);
        transcriptScroll = new JScrollPane(transcript);
        transcriptScroll.setBorder(null);
        transcriptScroll.getViewport().setBackground(Theme.BACKGROUND);
        transcriptScroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        transcriptScroll.getVerticalScrollBar().setUnitIncrement(18);
        transcriptScroll.getViewport().addComponentListener(new ComponentAdapter() {
            @Override public void componentResized(ComponentEvent event) {
                relayoutBubbles();
            }
        });
        add(buildApiPanel(onSave, onFetch), BorderLayout.NORTH);
        add(buildChatPanel(onAsk, onClearChat), BorderLayout.CENTER);
        citationList.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent event) {
                if (event.getClickCount() == 2) onOpenCitation.run();
            }
        });
    }

    public ApiConfig config() {
        Object model = apiModel.isEditable() ? apiModel.getEditor().getItem() : apiModel.getSelectedItem();
        return new ApiConfig(apiUrl.getText(), new String(apiKey.getPassword()), model == null ? "" : model.toString());
    }

    public void config(ApiConfig config) {
        apiUrl.setText(config.baseUrl());
        apiKey.setText(config.apiKey());
        if (!config.model().isBlank()) apiModel.addItem(config.model());
        apiModel.setSelectedItem(config.model());
        apiStatus.setText(config.model().isBlank()
                ? "填写兼容 OpenAI 的 API 地址后获取模型"
                : "已保存模型：" + config.model());
    }

    public JTextArea questionArea() { return question; }
    public String question() { return question.getText().strip(); }
    public void clearQuestion() { question.setText(""); }
    public CitationView selectedCitation() { return citationList.getSelectedValue(); }
    public Object modelEditorValue() { return apiModel.getEditor().getItem(); }
    public boolean localOnly() { return localOnly.isSelected(); }
    public void localOnly(boolean value) { localOnly.setSelected(value); }

    public void models(List<String> models, Object previous) {
        apiModel.removeAllItems();
        models.forEach(apiModel::addItem);
        if (previous != null && models.contains(previous.toString())) apiModel.setSelectedItem(previous);
        else if (!models.isEmpty()) apiModel.setSelectedIndex(0);
    }

    public void apiStatus(String text, Color color) {
        apiStatus.setText(text);
        apiStatus.setForeground(color);
    }

    public void clearCitations() { citations.clear(); }

    public void citations(List<CitationView> values) {
        citations.clear();
        values.forEach(citations::addElement);
    }

    public void conversationTitle(String text) { conversationTitle.setText(text); }
    public void conversationMeta(String text) { conversationMeta.setText(text); }

    public void showMessages(List<ChatMessage> messages) {
        resetTranscript();
        for (ChatMessage message : messages) {
            addBubble(message.role() == ChatMessage.Role.USER, message.content(), false);
        }
        if (bubbles.isEmpty()) {
            transcript.add(emptyState);
        }
        revalidateTranscript(true);
    }

    public void beginTurn(String userText) {
        removeEmptyState();
        clearCitations();
        addBubble(true, userText, false);
        streamingAssistant = addBubble(false, "", true);
        conversationTitle("正在检索并生成回答…");
        conversationMeta("本轮将重新执行 freshness 检查与知识检索");
        revalidateTranscript(true);
    }

    public void appendAssistantDelta(String delta) {
        if (streamingAssistant == null) {
            removeEmptyState();
            streamingAssistant = addBubble(false, "", true);
        }
        streamingAssistant.append(delta);
        revalidateTranscript(true);
    }

    public void finishAssistant(String fullText, String model) {
        if (streamingAssistant != null) {
            if (streamingAssistant.isEmpty() && fullText != null && !fullText.isBlank()) {
                streamingAssistant.setText(fullText);
            }
            streamingAssistant.setStreaming(false);
            streamingAssistant = null;
        } else if (fullText != null && !fullText.isBlank()) {
            addBubble(false, fullText, false);
        }
        conversationTitle(model == null || model.isBlank() ? "对话" : "对话 · " + model);
        conversationMeta("多轮上下文已启用 · 历史不含引用片段");
        revalidateTranscript(true);
    }

    public void failAssistant(String message) {
        if (streamingAssistant != null) {
            streamingAssistant.setText(message == null || message.isBlank() ? "生成失败" : message);
            streamingAssistant.markError();
            streamingAssistant.setStreaming(false);
            streamingAssistant = null;
        } else {
            BubblePanel error = addBubble(false, message == null ? "生成失败" : message, false);
            error.markError();
        }
        conversationTitle("生成失败");
        conversationMeta("失败轮次不会写入多轮历史");
        revalidateTranscript(true);
    }

    public void stopAssistant() {
        if (streamingAssistant != null) {
            if (streamingAssistant.isEmpty()) {
                streamingAssistant.setText("（已停止）");
            }
            streamingAssistant.setStreaming(false);
            streamingAssistant = null;
        }
        conversationTitle("已停止");
        conversationMeta("已取消本轮生成");
        revalidateTranscript(false);
    }

    public void resetConversation(String title, String meta) {
        resetTranscript();
        transcript.add(emptyState);
        conversationTitle(title);
        conversationMeta(meta);
        clearCitations();
        revalidateTranscript(false);
    }

    public void asking(boolean value) {
        ask.setText(value ? "停止" : "发送");
        question.setEnabled(!value);
        clearChat.setEnabled(!value);
    }

    private void resetTranscript() {
        transcript.removeAll();
        bubbles.clear();
        streamingAssistant = null;
    }

    private void removeEmptyState() {
        if (emptyState.getParent() == transcript) {
            transcript.remove(emptyState);
        }
    }

    private BubblePanel addBubble(boolean user, String text, boolean streaming) {
        BubblePanel bubble = new BubblePanel(user, text, streaming);
        bubbles.add(bubble);

        JPanel row = new JPanel();
        row.setOpaque(false);
        row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.setBorder(new EmptyBorder(4, 0, 4, 0));

        if (user) {
            row.add(Box.createHorizontalGlue());
            row.add(bubble);
        } else {
            row.add(bubble);
            row.add(Box.createHorizontalGlue());
        }

        // Keep rows content-sized so BoxLayout does not stretch them into empty space.
        Dimension preferred = row.getPreferredSize();
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, preferred.height));

        transcript.add(row);
        return bubble;
    }

    private void relayoutBubbles() {
        int available = availableBubbleWidth();
        for (BubblePanel bubble : bubbles) {
            bubble.applyAvailableWidth(available);
        }
        for (Component child : transcript.getComponents()) {
            if (child instanceof JPanel row && child != emptyState) {
                Dimension preferred = row.getPreferredSize();
                row.setMaximumSize(new Dimension(Integer.MAX_VALUE, preferred.height));
            }
        }
        transcript.revalidate();
        transcript.repaint();
    }

    private int availableBubbleWidth() {
        int width = transcriptScroll.getViewport().getWidth();
        if (width <= 0) {
            width = transcript.getWidth();
        }
        if (width <= 0) {
            width = getWidth() > 0 ? Math.max(360, getWidth() - 280) : 720;
        }
        return Math.max(240, width - 32);
    }

    private void revalidateTranscript(boolean scrollToBottom) {
        relayoutBubbles();
        if (scrollToBottom) {
            SwingUtilities.invokeLater(() -> {
                JScrollBar bar = transcriptScroll.getVerticalScrollBar();
                bar.setValue(bar.getMaximum());
            });
        }
    }

    private JPanel buildApiPanel(Runnable onSave, Consumer<JButton> onFetch) {
        JPanel panel = new JPanel(new GridBagLayout());
        Theme.opaque(panel, Theme.PANEL_ALT);
        panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.BORDER), Theme.padding(12, 16, 12, 16)));
        GridBagConstraints c = new GridBagConstraints();
        c.gridy = 0;
        c.insets = new Insets(0, 0, 0, 9);
        c.fill = GridBagConstraints.HORIZONTAL;
        c.gridx = 0;
        c.weightx = 0.48;
        panel.add(labeled("API URL", apiUrl), c);
        c.gridx = 1;
        c.weightx = 0.27;
        panel.add(labeled("API Key", apiKey), c);
        c.gridx = 2;
        c.weightx = 0.18;
        apiModel.setEditable(true);
        panel.add(labeled("模型", apiModel), c);
        c.gridx = 3;
        c.weightx = 0;
        c.fill = GridBagConstraints.NONE;
        JButton fetch = button("获取模型", false);
        fetch.addActionListener(e -> onFetch.accept(fetch));
        panel.add(fetch, c);
        c.gridx = 4;
        c.insets = new Insets(0, 0, 0, 0);
        JButton save = button("保存", true);
        save.addActionListener(e -> onSave.run());
        panel.add(save, c);
        c.gridy = 1;
        c.gridx = 0;
        c.gridwidth = 3;
        c.insets = new Insets(7, 2, 0, 0);
        c.fill = GridBagConstraints.HORIZONTAL;
        apiStatus.setForeground(Theme.MUTED);
        apiStatus.setFont(Theme.UI_FONT.deriveFont(10f));
        panel.add(apiStatus, c);
        c.gridx = 3;
        c.gridwidth = 2;
        c.fill = GridBagConstraints.NONE;
        c.anchor = GridBagConstraints.EAST;
        localOnly.setOpaque(false);
        localOnly.setForeground(Theme.MUTED);
        localOnly.setFont(Theme.UI_FONT.deriveFont(10f));
        panel.add(localOnly, c);
        return panel;
    }

    private JPanel buildChatPanel(Runnable onAsk, Runnable onClearChat) {
        JPanel panel = new JPanel(new BorderLayout(0, 0));
        Theme.opaque(panel, Theme.BACKGROUND);
        panel.setBorder(Theme.padding(0, 0, 0, 0));

        JPanel header = new JPanel(new BorderLayout(12, 0));
        Theme.opaque(header, Theme.BACKGROUND);
        header.setBorder(Theme.padding(12, 18, 6, 18));
        JPanel titles = new JPanel();
        titles.setOpaque(false);
        titles.setLayout(new BoxLayout(titles, BoxLayout.Y_AXIS));
        conversationTitle.setForeground(Theme.TEXT);
        conversationTitle.setFont(Theme.UI_FONT.deriveFont(Font.BOLD, 16f));
        conversationMeta.setForeground(Theme.MUTED);
        conversationMeta.setFont(Theme.UI_FONT.deriveFont(11f));
        titles.add(conversationTitle);
        titles.add(Box.createVerticalStrut(3));
        titles.add(conversationMeta);
        header.add(titles, BorderLayout.CENTER);
        Theme.styleButton(clearChat, false);
        clearChat.setMargin(new Insets(7, 12, 7, 12));
        clearChat.addActionListener(e -> onClearChat.run());
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        actions.setOpaque(false);
        actions.add(clearChat);
        header.add(actions, BorderLayout.EAST);

        JPanel citationPanel = new JPanel(new BorderLayout(0, 8));
        Theme.opaque(citationPanel, Theme.PANEL);
        citationPanel.setBorder(Theme.padding(14, 12, 12, 12));
        JLabel citationTitle = new JLabel("本轮引用");
        citationTitle.setForeground(Theme.TEXT);
        citationTitle.setFont(Theme.UI_FONT.deriveFont(Font.BOLD, 12f));
        JLabel citationHint = new JLabel("双击打开文件 · 历史不保留片段");
        citationHint.setForeground(Theme.MUTED);
        citationHint.setFont(Theme.UI_FONT.deriveFont(10f));
        JPanel citationHeader = new JPanel(new BorderLayout());
        citationHeader.setOpaque(false);
        citationHeader.add(citationTitle, BorderLayout.NORTH);
        citationHeader.add(citationHint, BorderLayout.SOUTH);
        citationPanel.add(citationHeader, BorderLayout.NORTH);
        citationList.setBackground(Theme.PANEL);
        citationList.setFixedCellHeight(68);
        citationList.setCellRenderer(new CitationRenderer());
        citationPanel.add(scroll(citationList), BorderLayout.CENTER);
        citationPanel.setPreferredSize(new Dimension(260, 100));
        citationPanel.setMinimumSize(new Dimension(220, 100));

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, transcriptScroll, citationPanel);
        split.setResizeWeight(0.78);
        split.setDividerLocation(820);
        split.setDividerSize(1);
        split.setBorder(null);
        split.setBackground(Theme.BORDER);

        panel.add(header, BorderLayout.NORTH);
        panel.add(split, BorderLayout.CENTER);
        panel.add(composer(onAsk), BorderLayout.SOUTH);
        return panel;
    }

    private JPanel buildEmptyState() {
        JPanel panel = new JPanel();
        panel.setOpaque(false);
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.setBorder(Theme.padding(36, 24, 24, 24));
        JLabel badge = new JLabel("RAG CHAT");
        badge.setForeground(Theme.ACCENT);
        badge.setFont(Theme.UI_FONT.deriveFont(Font.BOLD, 11f));
        badge.setAlignmentX(Component.CENTER_ALIGNMENT);
        JLabel title = new JLabel("向当前知识库提问");
        title.setForeground(Theme.TEXT);
        title.setFont(Theme.UI_FONT.deriveFont(Font.BOLD, 20f));
        title.setAlignmentX(Component.CENTER_ALIGNMENT);
        JLabel body = new JLabel("<html><div style='text-align:center;width:420px;color:#919CA4;line-height:1.55'>"
                + "支持多轮追问。每一轮都会重新做 freshness 检查并检索知识库；"
                + "对话历史只保留问答文本，不会沿用旧引用片段。"
                + "</div></html>");
        body.setAlignmentX(Component.CENTER_ALIGNMENT);
        panel.add(badge);
        panel.add(Box.createVerticalStrut(10));
        panel.add(title);
        panel.add(Box.createVerticalStrut(10));
        panel.add(body);
        panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 180));
        return panel;
    }

    private JPanel composer(Runnable onAsk) {
        JPanel shell = new JPanel(new BorderLayout());
        Theme.opaque(shell, Theme.BACKGROUND);
        shell.setBorder(Theme.padding(8, 18, 14, 18));

        JPanel card = new JPanel(new BorderLayout(10, 0));
        Theme.opaque(card, Theme.PANEL_ALT);
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Theme.BORDER),
                Theme.padding(10, 12, 10, 12)));

        question.setLineWrap(true);
        question.setWrapStyleWord(true);
        question.setFont(Theme.UI_FONT.deriveFont(13.5f));
        question.setBackground(Theme.PANEL_ALT);
        question.setForeground(Theme.TEXT);
        question.setCaretColor(Theme.TEXT);
        question.setBorder(null);
        question.setRows(2);

        JScrollPane scroll = new JScrollPane(question);
        scroll.setBorder(null);
        scroll.getViewport().setBackground(Theme.PANEL_ALT);
        scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);

        Theme.styleButton(ask, true);
        ask.setPreferredSize(new Dimension(96, 44));
        ask.addActionListener(e -> onAsk.run());

        JLabel hint = new JLabel("Enter 发送 · Shift+Enter 换行 · 历史绑定知识库版本");
        hint.setForeground(Theme.MUTED);
        hint.setFont(Theme.UI_FONT.deriveFont(10f));
        hint.setBorder(new EmptyBorder(8, 2, 0, 0));

        card.add(scroll, BorderLayout.CENTER);
        card.add(ask, BorderLayout.EAST);
        shell.add(card, BorderLayout.CENTER);
        shell.add(hint, BorderLayout.SOUTH);
        return shell;
    }

    private static JPanel labeled(String text, Component component) {
        JPanel panel = new JPanel(new BorderLayout(0, 4));
        panel.setOpaque(false);
        JLabel label = new JLabel(text);
        label.setForeground(Theme.MUTED);
        label.setFont(Theme.UI_FONT.deriveFont(Font.BOLD, 9f));
        panel.add(label, BorderLayout.NORTH);
        panel.add(component, BorderLayout.CENTER);
        return panel;
    }

    private static JButton button(String text, boolean primary) {
        JButton button = new JButton(text);
        Theme.styleButton(button, primary);
        return button;
    }

    private static JScrollPane scroll(Component component) {
        JScrollPane pane = new JScrollPane(component);
        pane.setBorder(null);
        pane.getViewport().setBackground(Theme.PANEL);
        return pane;
    }

    private static final class BubblePanel extends JPanel {
        private final boolean user;
        private final JTextArea body = new JTextArea();
        private final JLabel role = new JLabel();
        private boolean streaming;
        private boolean error;
        private int availableWidth = 720;

        private BubblePanel(boolean user, String text, boolean streaming) {
            super(new BorderLayout(0, 4));
            this.user = user;
            this.streaming = streaming;
            setOpaque(false);
            setBorder(Theme.padding(BUBBLE_INNER_PAD_Y, BUBBLE_INNER_PAD_X, BUBBLE_INNER_PAD_Y, BUBBLE_INNER_PAD_X));
            role.setText(user ? "你" : "助手");
            role.setFont(Theme.UI_FONT.deriveFont(Font.BOLD, 10f));
            role.setForeground(user ? new Color(9, 30, 25) : Theme.ACCENT);
            body.setEditable(false);
            body.setLineWrap(true);
            body.setWrapStyleWord(true);
            body.setOpaque(false);
            body.setFont(Theme.UI_FONT.deriveFont(13.5f));
            body.setForeground(user ? new Color(9, 30, 25) : Theme.TEXT);
            body.setText(text == null ? "" : text);
            body.setBorder(null);
            body.setFocusable(false);
            add(role, BorderLayout.NORTH);
            add(body, BorderLayout.CENTER);
            setAlignmentX(user ? Component.RIGHT_ALIGNMENT : Component.LEFT_ALIGNMENT);
            setCursor(Cursor.getPredefinedCursor(Cursor.TEXT_CURSOR));
        }

        void append(String delta) {
            body.append(delta);
            invalidate();
        }

        void setText(String text) {
            body.setText(text == null ? "" : text);
            invalidate();
        }

        boolean isEmpty() {
            return body.getText().isBlank();
        }

        void setStreaming(boolean streaming) {
            this.streaming = streaming;
            repaint();
        }

        void markError() {
            this.error = true;
            role.setForeground(Theme.RED);
            body.setForeground(Theme.RED);
            repaint();
        }

        void applyAvailableWidth(int available) {
            this.availableWidth = Math.max(240, available);
            revalidate();
        }

        private int maxBubbleWidth() {
            int ratio = user ? USER_MAX_WIDTH_RATIO : ASSISTANT_MAX_WIDTH_RATIO;
            return Math.max(200, availableWidth * ratio / 100);
        }

        private int contentWidthFor(int bubbleWidth) {
            return Math.max(80, bubbleWidth - BUBBLE_INNER_PAD_X * 2);
        }

        private Dimension measure(int bubbleWidth) {
            int contentWidth = contentWidthFor(bubbleWidth);
            body.setSize(new Dimension(contentWidth, Short.MAX_VALUE));
            Dimension bodySize = body.getPreferredSize();
            int height = bodySize.height + role.getPreferredSize().height + BUBBLE_INNER_PAD_Y * 2 + 8;
            if (streaming) {
                height = Math.max(height, 56);
            }
            return new Dimension(bubbleWidth, height);
        }

        private int preferredBubbleWidth() {
            int max = maxBubbleWidth();
            String text = body.getText();
            if (text == null || text.isBlank()) {
                return user ? Math.min(max, 220) : Math.min(max, Math.max(280, availableWidth * 70 / 100));
            }

            // Prefer filling most of the chat column for multi-line answers (screenshot style).
            int lines = text.split("\\R", -1).length;
            boolean multiline = lines > 1 || text.length() > 48;
            if (!user && multiline) {
                return max;
            }

            // Single-line / short messages hug content, but stay readable.
            Font font = body.getFont();
            int textWidth = body.getFontMetrics(font).stringWidth(text.replace('\n', ' '));
            int desired = textWidth + BUBBLE_INNER_PAD_X * 2 + 8;
            int min = user ? 120 : 180;
            return Math.min(max, Math.max(min, desired));
        }

        @Override
        public Dimension getPreferredSize() {
            return measure(preferredBubbleWidth());
        }

        @Override
        public Dimension getMinimumSize() {
            return measure(Math.min(200, maxBubbleWidth()));
        }

        @Override
        public Dimension getMaximumSize() {
            Dimension preferred = getPreferredSize();
            return new Dimension(preferred.width, preferred.height);
        }

        @Override
        public void doLayout() {
            Dimension size = getPreferredSize();
            if (getWidth() != size.width || getHeight() != size.height) {
                setSize(size);
            }
            super.doLayout();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Color fill;
            if (error) {
                fill = new Color(60, 28, 28);
            } else if (user) {
                fill = Theme.ACCENT;
            } else {
                fill = Theme.PANEL_ALT;
            }
            int arc = user ? 20 : 16;
            g2.setColor(fill);
            g2.fillRoundRect(0, 0, getWidth(), getHeight(), arc, arc);
            if (!user && !error) {
                g2.setColor(Theme.BORDER);
                g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, arc, arc);
            }
            if (streaming) {
                g2.setColor(Theme.ACCENT_DARK);
                g2.fillOval(getWidth() - 18, getHeight() - 18, 8, 8);
            }
            g2.dispose();
            super.paintComponent(g);
        }
    }

    private static final class CitationRenderer extends JPanel implements javax.swing.ListCellRenderer<CitationView> {
        private final JLabel title = new JLabel();
        private final JLabel meta = new JLabel();

        private CitationRenderer() {
            super(new BorderLayout(0, 4));
            title.setFont(Theme.UI_FONT.deriveFont(Font.BOLD, 11f));
            meta.setFont(Theme.UI_FONT.deriveFont(9f));
            add(title, BorderLayout.CENTER);
            add(meta, BorderLayout.SOUTH);
        }

        @Override
        public Component getListCellRendererComponent(JList<? extends CitationView> list, CitationView value,
                                                      int index, boolean selected, boolean focused) {
            setBackground(selected ? Theme.HOVER : Theme.PANEL);
            setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.BORDER),
                    Theme.padding(8, 8, 8, 8)));
            title.setText("[" + value.number() + "] " + value.document().fileName());
            title.setForeground(Theme.TEXT);
            meta.setText(value.document().sourceLocation()
                    + "  ·  " + Math.round(value.score() * 100) + "%");
            meta.setForeground(Theme.MUTED);
            setToolTipText(value.document().path().toString());
            return this;
        }
    }
}
