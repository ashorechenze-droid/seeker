package com.simplerag.adapter.in.swing;

import com.simplerag.model.KnowledgeBase;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSplitPane;
import javax.swing.KeyStroke;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Insets;
import java.awt.Toolkit;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.nio.file.Path;

/** Window composition, page navigation and application-close flow. */
public final class MainFrame extends JFrame {
    private static final String SEARCH_MODE = "search";
    private static final String ASK_MODE = "ask";
    private final CardLayout modeLayout = new CardLayout();
    private final JPanel modeCards = new JPanel(modeLayout);
    private final JLabel currentKnowledge = new JLabel();
    private final JButton searchMode = new JButton("语义检索");
    private final JButton askMode = new JButton("知识问答");
    private final DesktopWorkspaceController workspace;

    public MainFrame(KnowledgeController knowledge, SearchController search, AskController ask,
                     BackgroundTaskCoordinator tasks, DesktopFileGateway files) {
        super("SimpleRAG - 本地语义知识库");
        this.workspace = new DesktopWorkspaceController(knowledge, search, ask, tasks, files,
                this::showCurrentKnowledge);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setMinimumSize(new Dimension(1120, 680));
        setSize(1440, 860);
        setLocationRelativeTo(null);
        setContentPane(buildContent());
        installNavigation();
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent event) { workspace.close(); }
        });
        showMode(SEARCH_MODE);
    }

    public void initializeKnowledge(Path demoRoot) { workspace.initializeKnowledge(demoRoot); }

    private JPanel buildContent() {
        JPanel content = new JPanel(new BorderLayout());
        Theme.opaque(content, Theme.BACKGROUND);
        content.add(buildHeader(), BorderLayout.NORTH);
        content.add(buildBody(), BorderLayout.CENTER);
        content.add(workspace.statusBar(), BorderLayout.SOUTH);
        return content;
    }

    private JPanel buildHeader() {
        JPanel header = new JPanel(new BorderLayout(24, 0));
        Theme.opaque(header, Theme.PANEL);
        header.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.BORDER), Theme.padding(11, 18, 11, 18)));
        JPanel brand = new JPanel(); brand.setOpaque(false); brand.setLayout(new BoxLayout(brand, BoxLayout.Y_AXIS));
        JLabel title = new JLabel("SimpleRAG"); title.setForeground(Theme.TEXT); title.setFont(Theme.UI_FONT.deriveFont(Font.BOLD, 20f));
        JLabel subtitle = new JLabel("LOCAL KNOWLEDGE WORKSPACE"); subtitle.setForeground(Theme.MUTED); subtitle.setFont(Theme.UI_FONT.deriveFont(Font.BOLD, 9f));
        brand.add(title); brand.add(subtitle); brand.setPreferredSize(new Dimension(220, 42));
        JPanel modes = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 2)); modes.setOpaque(false);
        styleModeButton(searchMode); styleModeButton(askMode); modes.add(searchMode); modes.add(askMode);
        currentKnowledge.setForeground(Theme.MUTED); currentKnowledge.setFont(Theme.UI_FONT.deriveFont(Font.BOLD, 12f));
        currentKnowledge.setBorder(Theme.padding(0, 8, 0, 4));
        header.add(brand, BorderLayout.WEST); header.add(modes, BorderLayout.CENTER); header.add(currentKnowledge, BorderLayout.EAST);
        return header;
    }

    private Component buildBody() {
        modeCards.setOpaque(true); modeCards.setBackground(Theme.BACKGROUND);
        modeCards.add(workspace.searchPanel(), SEARCH_MODE); modeCards.add(workspace.askPanel(), ASK_MODE);
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, workspace.knowledgePanel(), modeCards);
        split.setDividerLocation(270); split.setDividerSize(1); split.setResizeWeight(0); split.setBorder(null); split.setBackground(Theme.BORDER);
        return split;
    }

    private void installNavigation() {
        searchMode.addActionListener(event -> showMode(SEARCH_MODE));
        askMode.addActionListener(event -> showMode(ASK_MODE));
        getRootPane().getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_K,
                Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()), "focusSearch");
        getRootPane().getActionMap().put("focusSearch", new javax.swing.AbstractAction() {
            @Override public void actionPerformed(ActionEvent event) { showMode(SEARCH_MODE); workspace.focusSearch(); }
        });
    }

    private void showMode(String mode) {
        modeLayout.show(modeCards, mode); boolean search = SEARCH_MODE.equals(mode);
        searchMode.setBackground(search ? Theme.ACCENT_DARK : Theme.PANEL_ALT);
        askMode.setBackground(search ? Theme.PANEL_ALT : Theme.ACCENT_DARK);
        searchMode.setForeground(Theme.TEXT); askMode.setForeground(Theme.TEXT);
    }

    private void showCurrentKnowledge(KnowledgeBase knowledge) {
        currentKnowledge.setText(knowledge.name()); currentKnowledge.setToolTipText(knowledge.description());
    }

    private static void styleModeButton(JButton button) {
        Theme.styleButton(button, false); button.setMargin(new Insets(8, 16, 8, 16));
    }
}
