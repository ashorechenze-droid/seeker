package com.simplerag.adapter.in.swing;

import com.simplerag.model.KnowledgeBase;
import com.simplerag.model.KnowledgeStats;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.ListSelectionModel;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.event.ActionListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;

/**
 * Sidebar page and sole owner of library and build controls.
 *
 * <p>Source directories are not listed separately: they are the roots of the injected file
 * explorer, which shows the same directories plus their contents and index state.
 */
public final class KnowledgePanel extends JPanel {
    private final DefaultListModel<KnowledgeBase> knowledgeBases = new DefaultListModel<>();
    private final JList<KnowledgeBase> knowledgeBaseList = new JList<>(knowledgeBases);
    private final JLabel stats = new JLabel("0 个文件 · 0 个片段");
    private final JButton rebuild = new JButton("重建索引");
    private final JProgressBar progress = new JProgressBar();
    private boolean refreshing;

    public KnowledgePanel(Runnable onCreate, Runnable onEdit, Runnable onDelete, Runnable onSelect,
                          Runnable onRebuild, JComponent explorer) {
        super(new BorderLayout(0, 14));
        Theme.opaque(this, Theme.PANEL);
        setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 0, 1, Theme.BORDER), Theme.padding(16, 14, 14, 14)));
        JPanel libraries = new JPanel(new BorderLayout(0, 8)); libraries.setOpaque(false);
        libraries.setPreferredSize(new Dimension(250, 196));
        libraries.add(heading("知识库", "新建", event -> onCreate.run()), BorderLayout.NORTH);
        knowledgeBaseList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION); knowledgeBaseList.setFixedCellHeight(56);
        knowledgeBaseList.setBackground(Theme.PANEL); knowledgeBaseList.setCellRenderer(new KnowledgeBaseRenderer());
        libraries.add(scroll(knowledgeBaseList), BorderLayout.CENTER);
        JPanel actions = new JPanel(new GridLayout(1, 2, 7, 0)); actions.setOpaque(false);
        JButton edit = button("编辑"); edit.addActionListener(event -> onEdit.run());
        JButton delete = button("删除"); delete.addActionListener(event -> onDelete.run());
        actions.add(edit); actions.add(delete); libraries.add(actions, BorderLayout.SOUTH);
        JPanel footer = new JPanel(); footer.setOpaque(false); footer.setLayout(new BoxLayout(footer, BoxLayout.Y_AXIS));
        stats.setForeground(Theme.MUTED); stats.setFont(Theme.UI_FONT.deriveFont(11f)); stats.setAlignmentX(Component.LEFT_ALIGNMENT);
        rebuild.setAlignmentX(Component.LEFT_ALIGNMENT); rebuild.setMaximumSize(new Dimension(Integer.MAX_VALUE, 34)); Theme.styleButton(rebuild, true); rebuild.addActionListener(event -> onRebuild.run());
        progress.setVisible(false); progress.setForeground(Theme.ACCENT); progress.setBackground(Theme.BACKGROUND); progress.setBorderPainted(false); progress.setMaximumSize(new Dimension(Integer.MAX_VALUE, 4));
        footer.add(stats); footer.add(Box.createVerticalStrut(10)); footer.add(rebuild); footer.add(Box.createVerticalStrut(10)); footer.add(progress);
        add(libraries, BorderLayout.NORTH); add(explorer, BorderLayout.CENTER); add(footer, BorderLayout.SOUTH);
        knowledgeBaseList.addListSelectionListener(event -> { if (!event.getValueIsAdjusting() && !refreshing) onSelect.run(); });
        knowledgeBaseList.addMouseListener(new MouseAdapter() { @Override public void mouseClicked(MouseEvent event) { if (event.getClickCount() == 2) onEdit.run(); } });
    }

    public KnowledgeBase selectedKnowledgeBase() { return knowledgeBaseList.getSelectedValue(); }
    public void knowledgeBases(List<KnowledgeBase> values, KnowledgeBase current) {
        refreshing = true; knowledgeBases.clear(); values.forEach(knowledgeBases::addElement);
        if (current != null) for (int i = 0; i < knowledgeBases.size(); i++) if (knowledgeBases.get(i).id().equals(current.id())) { knowledgeBaseList.setSelectedIndex(i); break; }
        refreshing = false;
    }
    public void stats(KnowledgeStats value) { stats.setText(value.files() + " 个文件  ·  " + value.chunks() + " 个片段"); }
    public void indexing(boolean value) { rebuild.setEnabled(!value); knowledgeBaseList.setEnabled(!value); progress.setVisible(value); progress.setIndeterminate(value); }
    public void progress(int processed, int total) { progress.setIndeterminate(false); progress.setMaximum(Math.max(1, total)); progress.setValue(processed); }

    private static JPanel heading(String title, String action, ActionListener listener) { JPanel panel = new JPanel(new BorderLayout()); panel.setOpaque(false); JLabel label = new JLabel(title); label.setForeground(Theme.TEXT); label.setFont(Theme.UI_FONT.deriveFont(Font.BOLD, 12f)); JButton button = button(action); button.addActionListener(listener); panel.add(label, BorderLayout.WEST); panel.add(button, BorderLayout.EAST); return panel; }
    private static JButton button(String text) { JButton button = new JButton(text); Theme.styleButton(button, false); return button; }
    private static JScrollPane scroll(Component component) { JScrollPane pane = new JScrollPane(component); pane.setBorder(null); pane.getViewport().setBackground(Theme.PANEL); return pane; }
    private static final class KnowledgeBaseRenderer extends JPanel implements javax.swing.ListCellRenderer<KnowledgeBase> { private final JLabel name = new JLabel(); private final JLabel description = new JLabel();
        private KnowledgeBaseRenderer() { super(new BorderLayout(0, 3)); name.setFont(Theme.UI_FONT.deriveFont(Font.BOLD, 12f)); description.setFont(Theme.UI_FONT.deriveFont(9f)); add(name, BorderLayout.CENTER); add(description, BorderLayout.SOUTH); }
        @Override public Component getListCellRendererComponent(JList<? extends KnowledgeBase> list, KnowledgeBase value, int index, boolean selected, boolean focused) { setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, selected ? 3 : 0, 0, 0, Theme.ACCENT), Theme.padding(8, selected ? 9 : 12, 8, 8))); setBackground(selected ? Theme.HOVER : Theme.PANEL); name.setText(value.name()); name.setForeground(Theme.TEXT); description.setText(value.description().isBlank() ? "本地知识库" : value.description()); description.setForeground(Theme.MUTED); return this; } }
}
