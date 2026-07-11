package com.simplerag.adapter.in.swing;

import com.simplerag.application.dto.CitationView;
import com.simplerag.rag.ApiConfig;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.function.Consumer;

/** Knowledge-question page and sole owner of its Swing control state. */
public final class AskPanel extends JPanel {
    private final JTextField apiUrl = new JTextField();
    private final JPasswordField apiKey = new JPasswordField();
    private final JComboBox<String> apiModel = new JComboBox<>();
    private final JLabel apiStatus = new JLabel("API 未连接");
    private final JTextArea question = new JTextArea(3, 30);
    private final JTextArea answer = new JTextArea();
    private final JLabel answerTitle = new JLabel("知识库回答");
    private final JButton ask = new JButton("发送问题");
    private final DefaultListModel<CitationView> citations = new DefaultListModel<>();
    private final JList<CitationView> citationList = new JList<>(citations);

    public AskPanel(Runnable onAsk, Runnable onSave, Consumer<JButton> onFetch, Runnable onOpenCitation) {
        super(new BorderLayout());
        Theme.opaque(this, Theme.BACKGROUND);
        add(buildApiPanel(onSave, onFetch), BorderLayout.NORTH);
        add(buildAnswerPanel(onAsk), BorderLayout.CENTER);
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
        apiStatus.setText(config.model().isBlank() ? "填写兼容 OpenAI 的 API 地址后获取模型" : "已保存模型：" + config.model());
    }

    public JTextArea questionArea() { return question; }
    public String question() { return question.getText().strip(); }
    public CitationView selectedCitation() { return citationList.getSelectedValue(); }
    public Object modelEditorValue() { return apiModel.getEditor().getItem(); }
    public void models(List<String> models, Object previous) {
        apiModel.removeAllItems();
        models.forEach(apiModel::addItem);
        if (previous != null && models.contains(previous.toString())) apiModel.setSelectedItem(previous);
        else if (!models.isEmpty()) apiModel.setSelectedIndex(0);
    }
    public void apiStatus(String text, Color color) { apiStatus.setText(text); apiStatus.setForeground(color); }
    public void clearCitations() { citations.clear(); }
    public void citations(List<CitationView> values) { citations.clear(); values.forEach(citations::addElement); }
    public void answerTitle(String text) { answerTitle.setText(text); }
    public void answer(String text) { answer.setText(text); }
    public void appendAnswer(String text) { answer.append(text); }
    public int answerLength() { return answer.getDocument().getLength(); }
    public void answerCaret(int position) { answer.setCaretPosition(position); }
    public void asking(boolean value) { ask.setText(value ? "停止" : "发送问题"); question.setEnabled(!value); }

    private JPanel buildApiPanel(Runnable onSave, Consumer<JButton> onFetch) {
        JPanel panel = new JPanel(new GridBagLayout());
        Theme.opaque(panel, Theme.PANEL_ALT);
        panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.BORDER), Theme.padding(12, 16, 12, 16)));
        GridBagConstraints c = new GridBagConstraints();
        c.gridy = 0; c.insets = new Insets(0, 0, 0, 9); c.fill = GridBagConstraints.HORIZONTAL;
        c.gridx = 0; c.weightx = 0.48; panel.add(labeled("API URL", apiUrl), c);
        c.gridx = 1; c.weightx = 0.27; panel.add(labeled("API KEY", apiKey), c);
        c.gridx = 2; c.weightx = 0.25; apiModel.setEditable(true); panel.add(labeled("模型", apiModel), c);
        c.gridx = 3; c.weightx = 0; JButton fetch = button("获取模型", false); fetch.addActionListener(e -> onFetch.accept(fetch)); panel.add(fetch, c);
        c.gridx = 4; c.insets = new Insets(0, 0, 0, 0); JButton save = button("保存", true); save.addActionListener(e -> onSave.run()); panel.add(save, c);
        c.gridy = 1; c.gridx = 0; c.gridwidth = 5; c.insets = new Insets(7, 2, 0, 0);
        apiStatus.setForeground(Theme.MUTED); apiStatus.setFont(Theme.UI_FONT.deriveFont(10f)); panel.add(apiStatus, c);
        return panel;
    }

    private JPanel buildAnswerPanel(Runnable onAsk) {
        JPanel panel = new JPanel(new BorderLayout(0, 10));
        Theme.opaque(panel, Theme.BACKGROUND); panel.setBorder(Theme.padding(18, 18, 12, 18));
        answerTitle.setForeground(Theme.TEXT); answerTitle.setFont(Theme.UI_FONT.deriveFont(Font.BOLD, 16f));
        answer.setEditable(false); answer.setLineWrap(true); answer.setWrapStyleWord(true);
        answer.setFont(Theme.UI_FONT.deriveFont(14f)); answer.setBackground(Theme.PANEL_ALT);
        answer.setForeground(Theme.TEXT); answer.setBorder(Theme.padding(18, 18, 18, 18));
        answer.setText("选择当前知识库中的内容作为上下文，然后通过配置的模型生成带引用回答。");
        JPanel citationPanel = new JPanel(new BorderLayout(0, 8));
        Theme.opaque(citationPanel, Theme.PANEL); citationPanel.setBorder(Theme.padding(14, 12, 12, 12));
        JLabel title = new JLabel("引用资料"); title.setForeground(Theme.TEXT); title.setFont(Theme.UI_FONT.deriveFont(Font.BOLD, 12f));
        citationPanel.add(title, BorderLayout.NORTH); citationList.setBackground(Theme.PANEL);
        citationList.setFixedCellHeight(68); citationList.setCellRenderer(new CitationRenderer());
        citationPanel.add(scroll(citationList), BorderLayout.CENTER); citationPanel.setMinimumSize(new Dimension(240, 100));
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, scroll(answer), citationPanel);
        split.setResizeWeight(0.75); split.setDividerLocation(760); split.setDividerSize(1); split.setBorder(null);
        panel.add(answerTitle, BorderLayout.NORTH); panel.add(split, BorderLayout.CENTER); panel.add(composer(onAsk), BorderLayout.SOUTH);
        return panel;
    }

    private JPanel composer(Runnable onAsk) {
        JPanel panel = new JPanel(new BorderLayout(10, 0)); Theme.opaque(panel, Theme.BACKGROUND); panel.setBorder(Theme.padding(12, 0, 0, 0));
        question.setLineWrap(true); question.setWrapStyleWord(true); question.setFont(Theme.UI_FONT.deriveFont(13f));
        question.setBackground(Theme.PANEL_ALT); question.setForeground(Theme.TEXT); question.setBorder(Theme.padding(10, 12, 10, 12));
        JScrollPane scroll = scroll(question); scroll.setBorder(BorderFactory.createLineBorder(Theme.BORDER));
        Theme.styleButton(ask, true); ask.setPreferredSize(new Dimension(105, 64)); ask.addActionListener(e -> onAsk.run());
        panel.add(scroll, BorderLayout.CENTER); panel.add(ask, BorderLayout.EAST); return panel;
    }

    private static JPanel labeled(String text, Component component) {
        JPanel panel = new JPanel(new BorderLayout(0, 4)); panel.setOpaque(false);
        JLabel label = new JLabel(text); label.setForeground(Theme.MUTED); label.setFont(Theme.UI_FONT.deriveFont(Font.BOLD, 9f));
        panel.add(label, BorderLayout.NORTH); panel.add(component, BorderLayout.CENTER); return panel;
    }
    private static JButton button(String text, boolean primary) { JButton button = new JButton(text); Theme.styleButton(button, primary); return button; }
    private static JScrollPane scroll(Component component) { JScrollPane pane = new JScrollPane(component); pane.setBorder(null); pane.getViewport().setBackground(Theme.PANEL); return pane; }

    private static final class CitationRenderer extends JPanel implements javax.swing.ListCellRenderer<CitationView> {
        private final JLabel title = new JLabel(); private final JLabel meta = new JLabel();
        private CitationRenderer() { super(new BorderLayout(0, 4)); title.setFont(Theme.UI_FONT.deriveFont(Font.BOLD, 11f)); meta.setFont(Theme.UI_FONT.deriveFont(9f)); add(title, BorderLayout.CENTER); add(meta, BorderLayout.SOUTH); }
        @Override public Component getListCellRendererComponent(JList<? extends CitationView> list, CitationView value, int index, boolean selected, boolean focused) {
            setBackground(selected ? Theme.HOVER : Theme.PANEL); setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.BORDER), Theme.padding(8, 8, 8, 8)));
            title.setText("[" + value.number() + "] " + value.document().fileName()); title.setForeground(Theme.TEXT);
            meta.setText("L" + value.document().startLine() + "-" + value.document().endLine() + "  ·  " + Math.round(value.score() * 100) + "%"); meta.setForeground(Theme.MUTED); setToolTipText(value.document().path().toString()); return this;
        }
    }
}
