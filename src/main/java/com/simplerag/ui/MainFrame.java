package com.simplerag.ui;

import com.simplerag.model.DocumentChunk;
import com.simplerag.model.KnowledgeBase;
import com.simplerag.model.RagAnswer;
import com.simplerag.model.RagCitation;
import com.simplerag.model.SearchResult;
import com.simplerag.model.SemanticHighlight;
import com.simplerag.rag.ApiConfig;
import com.simplerag.search.SemanticSearchEngine;
import com.simplerag.service.KnowledgeService;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultComboBoxModel;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.SwingWorker;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.DefaultHighlighter;
import javax.swing.text.Highlighter;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class MainFrame extends JFrame {
    private static final String SEARCH_MODE = "search";
    private static final String ASK_MODE = "ask";
    private static final Pattern HIGHLIGHT_TERM = Pattern.compile("[\\p{L}\\p{N}_-]{2,}");

    private final KnowledgeService service;
    private final CardLayout modeLayout = new CardLayout();
    private final JPanel modeCards = new JPanel(modeLayout);
    private final DefaultListModel<KnowledgeBase> knowledgeBaseModel = new DefaultListModel<>();
    private final DefaultListModel<Path> sourceModel = new DefaultListModel<>();
    private final DefaultListModel<SearchResult> resultModel = new DefaultListModel<>();
    private final DefaultListModel<RagCitation> citationModel = new DefaultListModel<>();
    private final JList<KnowledgeBase> knowledgeBaseList = new JList<>(knowledgeBaseModel);
    private final JList<Path> sourceList = new JList<>(sourceModel);
    private final JList<SearchResult> resultList = new JList<>(resultModel);
    private final JList<RagCitation> citationList = new JList<>(citationModel);
    private final PromptTextField searchField = new PromptTextField("搜索笔记、代码和配置...");
    private final JComboBox<String> extensionFilter = new JComboBox<>();
    private final JLabel currentKnowledgeLabel = new JLabel();
    private final JLabel resultSummary = new JLabel("输入内容开始检索");
    private final JLabel statsLabel = new JLabel("0 个文件 · 0 个片段");
    private final JLabel statusLabel = new JLabel("就绪");
    private final JLabel semanticLabel = new JLabel("语义模型检查中");
    private final JProgressBar progressBar = new JProgressBar();
    private final JTextArea previewArea = new JTextArea();
    private final JTextArea lineNumbers = new JTextArea();
    private final JLabel previewTitle = new JLabel("选择一个结果");
    private final JLabel previewMeta = new JLabel(" ");
    private final JButton openButton = new JButton("打开");
    private final JButton locateButton = new JButton("目录");
    private final JButton copyButton = new JButton("复制");
    private final JButton rebuildButton = new JButton("重建索引");
    private final JButton searchModeButton = new JButton("语义检索");
    private final JButton askModeButton = new JButton("知识问答");
    private final JTextField apiUrlField = new JTextField();
    private final JPasswordField apiKeyField = new JPasswordField();
    private final JComboBox<String> apiModelCombo = new JComboBox<>();
    private final JLabel apiStatusLabel = new JLabel("API 未连接");
    private final JTextArea questionArea = new JTextArea(3, 30);
    private final JTextArea answerArea = new JTextArea();
    private final JLabel answerTitle = new JLabel("知识库回答");
    private final JButton askButton = new JButton("发送问题");
    private final Timer searchTimer;
    private boolean refreshingKnowledgeBases;
    private SwingWorker<List<SearchResult>, Void> searchWorker;
    private SwingWorker<List<SemanticHighlight>, Void> highlightWorker;

    public MainFrame(KnowledgeService service) {
        super("SimpleRAG - 本地语义知识库");
        this.service = service;
        this.searchTimer = new Timer(180, event -> performSearch());
        searchTimer.setRepeats(false);

        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setMinimumSize(new Dimension(1120, 680));
        setSize(1440, 860);
        setLocationRelativeTo(null);
        setContentPane(buildContent());
        installActions();
        loadApiConfig();
        refreshAll();
        showMode(SEARCH_MODE);
        setPreview(null);
    }

    private JPanel buildContent() {
        JPanel content = new JPanel(new BorderLayout());
        Theme.opaque(content, Theme.BACKGROUND);
        content.add(buildHeader(), BorderLayout.NORTH);
        content.add(buildBody(), BorderLayout.CENTER);
        content.add(buildStatusBar(), BorderLayout.SOUTH);
        return content;
    }

    private JPanel buildHeader() {
        JPanel header = new JPanel(new BorderLayout(24, 0));
        Theme.opaque(header, Theme.PANEL);
        header.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.BORDER), Theme.padding(11, 18, 11, 18)));

        JPanel brand = new JPanel();
        brand.setOpaque(false);
        brand.setLayout(new BoxLayout(brand, BoxLayout.Y_AXIS));
        JLabel title = new JLabel("SimpleRAG");
        title.setForeground(Theme.TEXT);
        title.setFont(Theme.UI_FONT.deriveFont(Font.BOLD, 20f));
        JLabel subtitle = new JLabel("LOCAL KNOWLEDGE WORKSPACE");
        subtitle.setForeground(Theme.MUTED);
        subtitle.setFont(Theme.UI_FONT.deriveFont(Font.BOLD, 9f));
        brand.add(title);
        brand.add(subtitle);
        brand.setPreferredSize(new Dimension(220, 42));

        JPanel modes = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 2));
        modes.setOpaque(false);
        styleModeButton(searchModeButton);
        styleModeButton(askModeButton);
        modes.add(searchModeButton);
        modes.add(askModeButton);

        currentKnowledgeLabel.setForeground(Theme.MUTED);
        currentKnowledgeLabel.setFont(Theme.UI_FONT.deriveFont(Font.BOLD, 12f));
        currentKnowledgeLabel.setBorder(Theme.padding(0, 8, 0, 4));
        header.add(brand, BorderLayout.WEST);
        header.add(modes, BorderLayout.CENTER);
        header.add(currentKnowledgeLabel, BorderLayout.EAST);
        return header;
    }

    private Component buildBody() {
        modeCards.setOpaque(true);
        modeCards.setBackground(Theme.BACKGROUND);
        modeCards.add(buildSearchPage(), SEARCH_MODE);
        modeCards.add(buildAskPage(), ASK_MODE);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, buildSidebar(), modeCards);
        split.setDividerLocation(270);
        split.setDividerSize(1);
        split.setResizeWeight(0);
        split.setBorder(null);
        split.setBackground(Theme.BORDER);
        return split;
    }

    private JPanel buildSidebar() {
        JPanel sidebar = new JPanel(new BorderLayout(0, 14));
        Theme.opaque(sidebar, Theme.PANEL);
        sidebar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 0, 1, Theme.BORDER), Theme.padding(16, 14, 14, 14)));

        JPanel libraries = new JPanel(new BorderLayout(0, 8));
        libraries.setOpaque(false);
        libraries.add(sidebarHeading("知识库", "新建", event -> createKnowledgeBase()), BorderLayout.NORTH);
        knowledgeBaseList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        knowledgeBaseList.setFixedCellHeight(62);
        knowledgeBaseList.setBackground(Theme.PANEL);
        knowledgeBaseList.setCellRenderer(new KnowledgeBaseRenderer());
        libraries.add(scroll(knowledgeBaseList), BorderLayout.CENTER);

        JPanel libraryActions = new JPanel(new GridLayout(1, 2, 7, 0));
        libraryActions.setOpaque(false);
        JButton edit = compactButton("编辑", false);
        edit.addActionListener(event -> editKnowledgeBase());
        JButton delete = compactButton("删除", false);
        delete.addActionListener(event -> deleteKnowledgeBase());
        libraryActions.add(edit);
        libraryActions.add(delete);
        libraries.add(libraryActions, BorderLayout.SOUTH);

        JPanel sources = new JPanel(new BorderLayout(0, 8));
        sources.setOpaque(false);
        sources.setPreferredSize(new Dimension(230, 220));
        sources.add(sidebarHeading("数据源", "添加", this::chooseSource), BorderLayout.NORTH);
        sourceList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        sourceList.setFixedCellHeight(38);
        sourceList.setBackground(Theme.PANEL);
        sourceList.setCellRenderer(new SourceRenderer());
        sources.add(scroll(sourceList), BorderLayout.CENTER);
        JButton removeSource = compactButton("移除所选目录", false);
        removeSource.addActionListener(event -> removeSelectedSource());
        sources.add(removeSource, BorderLayout.SOUTH);

        JPanel top = new JPanel(new BorderLayout(0, 18));
        top.setOpaque(false);
        top.add(libraries, BorderLayout.CENTER);
        top.add(sources, BorderLayout.SOUTH);

        JPanel footer = new JPanel();
        footer.setOpaque(false);
        footer.setLayout(new BoxLayout(footer, BoxLayout.Y_AXIS));
        statsLabel.setForeground(Theme.MUTED);
        statsLabel.setFont(Theme.UI_FONT.deriveFont(11f));
        statsLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        rebuildButton.setAlignmentX(Component.LEFT_ALIGNMENT);
        rebuildButton.setMaximumSize(new Dimension(Integer.MAX_VALUE, 34));
        Theme.styleButton(rebuildButton, true);
        rebuildButton.addActionListener(event -> rebuildIndex());
        progressBar.setVisible(false);
        progressBar.setForeground(Theme.ACCENT);
        progressBar.setBackground(Theme.BACKGROUND);
        progressBar.setBorderPainted(false);
        progressBar.setMaximumSize(new Dimension(Integer.MAX_VALUE, 4));
        footer.add(statsLabel);
        footer.add(Box.createVerticalStrut(10));
        footer.add(rebuildButton);
        footer.add(Box.createVerticalStrut(10));
        footer.add(progressBar);

        sidebar.add(top, BorderLayout.CENTER);
        sidebar.add(footer, BorderLayout.SOUTH);
        return sidebar;
    }

    private JPanel buildSearchPage() {
        JPanel page = new JPanel(new BorderLayout());
        Theme.opaque(page, Theme.BACKGROUND);
        page.add(buildSearchToolbar(), BorderLayout.NORTH);

        JSplitPane details = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, buildResults(), buildPreview());
        details.setDividerLocation(430);
        details.setDividerSize(1);
        details.setResizeWeight(0.38);
        details.setBorder(null);
        details.setBackground(Theme.BORDER);
        page.add(details, BorderLayout.CENTER);
        return page;
    }

    private JPanel buildSearchToolbar() {
        JPanel toolbar = new JPanel(new BorderLayout(12, 0));
        Theme.opaque(toolbar, Theme.PANEL_ALT);
        toolbar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.BORDER), Theme.padding(13, 16, 13, 16)));
        searchField.setPreferredSize(new Dimension(560, 39));
        searchField.setToolTipText("输入自然语言、关键词或代码标识符");
        extensionFilter.setModel(new DefaultComboBoxModel<>(new String[]{"全部"}));
        extensionFilter.setPreferredSize(new Dimension(115, 36));
        toolbar.add(searchField, BorderLayout.CENTER);
        toolbar.add(extensionFilter, BorderLayout.EAST);
        return toolbar;
    }

    private JPanel buildResults() {
        JPanel panel = new JPanel(new BorderLayout(0, 10));
        Theme.opaque(panel, Theme.BACKGROUND);
        panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 0, 1, Theme.BORDER), Theme.padding(15, 14, 10, 14)));
        JPanel heading = new JPanel(new BorderLayout());
        heading.setOpaque(false);
        heading.add(sectionTitle("检索结果"), BorderLayout.WEST);
        resultSummary.setForeground(Theme.MUTED);
        resultSummary.setFont(Theme.UI_FONT.deriveFont(11f));
        heading.add(resultSummary, BorderLayout.EAST);
        resultList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        resultList.setFixedCellHeight(108);
        resultList.setBackground(Theme.BACKGROUND);
        resultList.setCellRenderer(new ResultRenderer());
        panel.add(heading, BorderLayout.NORTH);
        panel.add(scroll(resultList), BorderLayout.CENTER);
        return panel;
    }

    private JPanel buildPreview() {
        JPanel panel = new JPanel(new BorderLayout(0, 12));
        Theme.opaque(panel, Theme.PANEL_ALT);
        panel.setBorder(Theme.padding(15, 16, 12, 16));
        JPanel titlePanel = new JPanel(new BorderLayout(12, 4));
        titlePanel.setOpaque(false);
        JPanel labels = new JPanel();
        labels.setOpaque(false);
        labels.setLayout(new BoxLayout(labels, BoxLayout.Y_AXIS));
        previewTitle.setForeground(Theme.TEXT);
        previewTitle.setFont(Theme.UI_FONT.deriveFont(Font.BOLD, 15f));
        previewMeta.setForeground(Theme.MUTED);
        previewMeta.setFont(Theme.UI_FONT.deriveFont(10f));
        labels.add(previewTitle);
        labels.add(Box.createVerticalStrut(3));
        labels.add(previewMeta);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 5, 0));
        actions.setOpaque(false);
        for (JButton button : List.of(copyButton, locateButton, openButton)) {
            Theme.styleButton(button, button == openButton);
            button.setMargin(new Insets(6, 10, 6, 10));
            actions.add(button);
        }
        titlePanel.add(labels, BorderLayout.CENTER);
        titlePanel.add(actions, BorderLayout.EAST);

        previewArea.setEditable(false);
        previewArea.setFont(Theme.MONO_FONT);
        previewArea.setBackground(Theme.PANEL_ALT);
        previewArea.setForeground(new Color(218, 226, 230));
        previewArea.setCaretColor(Theme.ACCENT);
        previewArea.setTabSize(4);
        previewArea.setBorder(Theme.padding(10, 10, 10, 10));
        lineNumbers.setEditable(false);
        lineNumbers.setFont(Theme.MONO_FONT);
        lineNumbers.setBackground(Theme.PANEL);
        lineNumbers.setForeground(new Color(104, 116, 124));
        lineNumbers.setBorder(Theme.padding(10, 8, 10, 8));
        lineNumbers.setFocusable(false);
        JScrollPane previewScroll = scroll(previewArea);
        previewScroll.setRowHeaderView(lineNumbers);
        previewScroll.setBorder(BorderFactory.createLineBorder(Theme.BORDER));
        panel.add(titlePanel, BorderLayout.NORTH);
        panel.add(previewScroll, BorderLayout.CENTER);
        return panel;
    }

    private JPanel buildAskPage() {
        JPanel page = new JPanel(new BorderLayout());
        Theme.opaque(page, Theme.BACKGROUND);
        page.add(buildApiPanel(), BorderLayout.NORTH);

        JPanel answerPanel = new JPanel(new BorderLayout(0, 10));
        Theme.opaque(answerPanel, Theme.BACKGROUND);
        answerPanel.setBorder(Theme.padding(18, 18, 12, 18));
        answerTitle.setForeground(Theme.TEXT);
        answerTitle.setFont(Theme.UI_FONT.deriveFont(Font.BOLD, 16f));
        answerArea.setEditable(false);
        answerArea.setLineWrap(true);
        answerArea.setWrapStyleWord(true);
        answerArea.setFont(Theme.UI_FONT.deriveFont(14f));
        answerArea.setBackground(Theme.PANEL_ALT);
        answerArea.setForeground(Theme.TEXT);
        answerArea.setBorder(Theme.padding(18, 18, 18, 18));
        answerArea.setText("选择当前知识库中的内容作为上下文，然后通过配置的模型生成带引用回答。");

        JPanel citationsPanel = new JPanel(new BorderLayout(0, 8));
        Theme.opaque(citationsPanel, Theme.PANEL);
        citationsPanel.setBorder(Theme.padding(14, 12, 12, 12));
        citationsPanel.add(sectionTitle("引用资料"), BorderLayout.NORTH);
        citationList.setBackground(Theme.PANEL);
        citationList.setFixedCellHeight(68);
        citationList.setCellRenderer(new CitationRenderer());
        citationsPanel.add(scroll(citationList), BorderLayout.CENTER);
        citationsPanel.setMinimumSize(new Dimension(240, 100));

        JSplitPane answerSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, scroll(answerArea), citationsPanel);
        answerSplit.setResizeWeight(0.75);
        answerSplit.setDividerLocation(760);
        answerSplit.setDividerSize(1);
        answerSplit.setBorder(null);
        answerPanel.add(answerTitle, BorderLayout.NORTH);
        answerPanel.add(answerSplit, BorderLayout.CENTER);
        answerPanel.add(buildQuestionComposer(), BorderLayout.SOUTH);
        page.add(answerPanel, BorderLayout.CENTER);
        return page;
    }

    private JPanel buildApiPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        Theme.opaque(panel, Theme.PANEL_ALT);
        panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.BORDER), Theme.padding(12, 16, 12, 16)));
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.gridy = 0;
        constraints.insets = new Insets(0, 0, 0, 9);
        constraints.fill = GridBagConstraints.HORIZONTAL;

        constraints.gridx = 0;
        constraints.weightx = 0.48;
        panel.add(labeledControl("API URL", apiUrlField), constraints);
        constraints.gridx = 1;
        constraints.weightx = 0.27;
        panel.add(labeledControl("API KEY", apiKeyField), constraints);
        constraints.gridx = 2;
        constraints.weightx = 0.25;
        apiModelCombo.setEditable(true);
        panel.add(labeledControl("模型", apiModelCombo), constraints);
        constraints.gridx = 3;
        constraints.weightx = 0;
        JButton fetch = compactButton("获取模型", false);
        fetch.addActionListener(event -> fetchModels(fetch));
        panel.add(fetch, constraints);
        constraints.gridx = 4;
        constraints.insets = new Insets(0, 0, 0, 0);
        JButton save = compactButton("保存", true);
        save.addActionListener(event -> saveApiConfig());
        panel.add(save, constraints);

        constraints.gridy = 1;
        constraints.gridx = 0;
        constraints.gridwidth = 5;
        constraints.insets = new Insets(7, 2, 0, 0);
        apiStatusLabel.setForeground(Theme.MUTED);
        apiStatusLabel.setFont(Theme.UI_FONT.deriveFont(10f));
        panel.add(apiStatusLabel, constraints);
        return panel;
    }

    private JPanel buildQuestionComposer() {
        JPanel composer = new JPanel(new BorderLayout(10, 0));
        Theme.opaque(composer, Theme.BACKGROUND);
        composer.setBorder(Theme.padding(12, 0, 0, 0));
        questionArea.setLineWrap(true);
        questionArea.setWrapStyleWord(true);
        questionArea.setFont(Theme.UI_FONT.deriveFont(13f));
        questionArea.setBackground(Theme.PANEL_ALT);
        questionArea.setForeground(Theme.TEXT);
        questionArea.setBorder(Theme.padding(10, 12, 10, 12));
        JScrollPane questionScroll = scroll(questionArea);
        questionScroll.setBorder(BorderFactory.createLineBorder(Theme.BORDER));
        Theme.styleButton(askButton, true);
        askButton.setPreferredSize(new Dimension(105, 64));
        askButton.addActionListener(event -> askQuestion());
        composer.add(questionScroll, BorderLayout.CENTER);
        composer.add(askButton, BorderLayout.EAST);
        return composer;
    }

    private JPanel buildStatusBar() {
        JPanel bar = new JPanel(new BorderLayout());
        Theme.opaque(bar, Theme.PANEL);
        bar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, Theme.BORDER), Theme.padding(6, 14, 6, 14)));
        statusLabel.setForeground(Theme.MUTED);
        statusLabel.setFont(Theme.UI_FONT.deriveFont(10f));
        semanticLabel.setFont(Theme.UI_FONT.deriveFont(Font.BOLD, 10f));
        JLabel local = new JLabel("LOCAL INDEX");
        local.setForeground(Theme.MUTED);
        local.setFont(Theme.UI_FONT.deriveFont(Font.BOLD, 9f));
        JPanel indicators = new JPanel(new FlowLayout(FlowLayout.RIGHT, 14, 0));
        indicators.setOpaque(false);
        indicators.add(semanticLabel);
        indicators.add(local);
        bar.add(statusLabel, BorderLayout.WEST);
        bar.add(indicators, BorderLayout.EAST);
        return bar;
    }

    private void installActions() {
        searchModeButton.addActionListener(event -> showMode(SEARCH_MODE));
        askModeButton.addActionListener(event -> showMode(ASK_MODE));
        searchField.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent event) { scheduleSearch(); }
            public void removeUpdate(DocumentEvent event) { scheduleSearch(); }
            public void changedUpdate(DocumentEvent event) { scheduleSearch(); }
        });
        extensionFilter.addActionListener(event -> scheduleSearch());
        resultList.addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting()) setPreview(resultList.getSelectedValue());
        });
        knowledgeBaseList.addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting() && !refreshingKnowledgeBases) switchKnowledgeBase();
        });
        knowledgeBaseList.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent event) {
                if (event.getClickCount() == 2) editKnowledgeBase();
            }
        });
        citationList.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent event) {
                if (event.getClickCount() == 2) openCitation();
            }
        });
        openButton.addActionListener(event -> openSelectedFile());
        locateButton.addActionListener(event -> openSelectedDirectory());
        copyButton.addActionListener(event -> copySelectedChunk());
        getRootPane().getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_K,
                Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()), "focusSearch");
        getRootPane().getActionMap().put("focusSearch", new javax.swing.AbstractAction() {
            @Override public void actionPerformed(ActionEvent event) {
                showMode(SEARCH_MODE);
                searchField.requestFocusInWindow();
                searchField.selectAll();
            }
        });
        questionArea.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER,
                Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()), "ask");
        questionArea.getActionMap().put("ask", new javax.swing.AbstractAction() {
            @Override public void actionPerformed(ActionEvent event) { askQuestion(); }
        });
    }

    public void initializeKnowledge(Path demoRoot) {
        if (service.roots().isEmpty() && service.stats().chunks() == 0 && Files.isDirectory(demoRoot)) {
            service.addSource(demoRoot.toAbsolutePath().normalize());
            refreshSourcesAndStats();
        }
        if (service.stats().chunks() == 0
                || (service.semanticModelConfigured() && !service.semanticEnabled() && !service.roots().isEmpty())) {
            rebuildIndex();
        }
    }

    private void showMode(String mode) {
        modeLayout.show(modeCards, mode);
        boolean search = SEARCH_MODE.equals(mode);
        searchModeButton.setBackground(search ? Theme.ACCENT_DARK : Theme.PANEL_ALT);
        askModeButton.setBackground(search ? Theme.PANEL_ALT : Theme.ACCENT_DARK);
        searchModeButton.setForeground(Theme.TEXT);
        askModeButton.setForeground(Theme.TEXT);
    }

    private void refreshAll() {
        refreshKnowledgeBases();
        refreshSourcesAndStats();
    }

    private void refreshKnowledgeBases() {
        refreshingKnowledgeBases = true;
        knowledgeBaseModel.clear();
        List<KnowledgeBase> items = service.knowledgeBases();
        items.forEach(knowledgeBaseModel::addElement);
        KnowledgeBase current = service.currentKnowledgeBase();
        if (current != null) {
            for (int i = 0; i < knowledgeBaseModel.size(); i++) {
                if (knowledgeBaseModel.get(i).id().equals(current.id())) {
                    knowledgeBaseList.setSelectedIndex(i);
                    break;
                }
            }
            currentKnowledgeLabel.setText(current.name());
            currentKnowledgeLabel.setToolTipText(current.description());
        }
        refreshingKnowledgeBases = false;
    }

    private void refreshSourcesAndStats() {
        sourceModel.clear();
        service.roots().forEach(sourceModel::addElement);
        KnowledgeService.KnowledgeStats stats = service.stats();
        statsLabel.setText(stats.files() + " 个文件  ·  " + stats.chunks() + " 个片段");
        semanticLabel.setText(service.semanticStatus());
        semanticLabel.setForeground(service.semanticEnabled() ? Theme.ACCENT : Theme.AMBER);
        Object selected = extensionFilter.getSelectedItem();
        DefaultComboBoxModel<String> filters = new DefaultComboBoxModel<>();
        filters.addElement("全部");
        service.extensions().forEach(filters::addElement);
        extensionFilter.setModel(filters);
        if (selected != null) extensionFilter.setSelectedItem(selected);
    }

    private void createKnowledgeBase() {
        KnowledgeBaseInput input = showKnowledgeBaseDialog("新建知识库", "", "");
        if (input == null) return;
        try {
            service.createKnowledgeBase(input.name(), input.description());
            clearWorkspace();
            refreshAll();
            statusLabel.setText("知识库已创建");
        } catch (RuntimeException failure) {
            showError("无法创建知识库", failure);
        }
    }

    private void editKnowledgeBase() {
        KnowledgeBase selected = knowledgeBaseList.getSelectedValue();
        if (selected == null) return;
        KnowledgeBaseInput input = showKnowledgeBaseDialog("编辑知识库", selected.name(), selected.description());
        if (input == null) return;
        try {
            service.updateCurrentKnowledgeBase(input.name(), input.description());
            refreshKnowledgeBases();
            statusLabel.setText("知识库信息已更新");
        } catch (RuntimeException failure) {
            showError("无法更新知识库", failure);
        }
    }

    private void deleteKnowledgeBase() {
        KnowledgeBase selected = knowledgeBaseList.getSelectedValue();
        if (selected == null) return;
        int answer = JOptionPane.showConfirmDialog(this,
                "删除知识库“" + selected.name() + "”？\n源文件不会被删除，但该知识库的索引会被清理。",
                "删除知识库", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
        if (answer != JOptionPane.OK_OPTION) return;
        try {
            service.deleteKnowledgeBase(selected.id());
            clearWorkspace();
            refreshAll();
            statusLabel.setText("知识库已删除");
        } catch (Exception failure) {
            showError("无法删除知识库", failure);
        }
    }

    private void switchKnowledgeBase() {
        KnowledgeBase selected = knowledgeBaseList.getSelectedValue();
        KnowledgeBase current = service.currentKnowledgeBase();
        if (selected == null || current != null && selected.id().equals(current.id())) return;
        try {
            service.selectKnowledgeBase(selected.id());
            clearWorkspace();
            refreshSourcesAndStats();
            currentKnowledgeLabel.setText(selected.name());
            statusLabel.setText("已切换到 “" + selected.name() + "”");
        } catch (RuntimeException failure) {
            showError("无法切换知识库", failure);
        }
    }

    private void chooseSource(ActionEvent event) {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("选择数据源目录");
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setMultiSelectionEnabled(true);
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            for (java.io.File selected : chooser.getSelectedFiles()) service.addSource(selected.toPath());
            refreshSourcesAndStats();
            rebuildIndex();
        }
    }

    private void removeSelectedSource() {
        Path selected = sourceList.getSelectedValue();
        if (selected == null) return;
        service.removeSource(selected);
        refreshSourcesAndStats();
        rebuildIndex();
    }

    private void rebuildIndex() {
        setIndexing(true, "正在扫描当前知识库...");
        new SwingWorker<SemanticSearchEngine.IndexReport, SemanticSearchEngine.IndexProgress>() {
            @Override protected SemanticSearchEngine.IndexReport doInBackground() throws Exception {
                return service.rebuildCurrent(this::publish);
            }

            @Override protected void process(List<SemanticSearchEngine.IndexProgress> values) {
                SemanticSearchEngine.IndexProgress latest = values.get(values.size() - 1);
                progressBar.setIndeterminate(false);
                progressBar.setMaximum(Math.max(1, latest.total()));
                progressBar.setValue(latest.processed());
                statusLabel.setText("正在" + latest.stage() + " " + latest.currentFile().getFileName()
                        + "  ·  " + latest.processed() + "/" + latest.total());
            }

            @Override protected void done() {
                try {
                    SemanticSearchEngine.IndexReport report = get();
                    refreshSourcesAndStats();
                    setIndexing(false, "索引完成：" + report.files() + " 个文件，" + report.chunks() + " 个片段");
                    performSearch();
                } catch (Exception failure) {
                    setIndexing(false, "索引失败");
                    showError("无法建立索引", failure);
                }
            }
        }.execute();
    }

    private void performSearch() {
        String query = searchField.getText().strip();
        if (searchWorker != null) searchWorker.cancel(true);
        resultModel.clear();
        if (query.isEmpty()) {
            resultSummary.setText("输入内容开始检索");
            setPreview(null);
            return;
        }
        String extension = String.valueOf(extensionFilter.getSelectedItem());
        resultSummary.setText("检索中...");
        searchWorker = new SwingWorker<>() {
            @Override protected List<SearchResult> doInBackground() {
                return service.search(query, 80, extension);
            }

            @Override protected void done() {
                if (isCancelled() || !query.equals(searchField.getText().strip())) return;
                try {
                    List<SearchResult> results = get();
                    results.forEach(resultModel::addElement);
                    resultSummary.setText(results.size() + " 个匹配");
                    statusLabel.setText(results.isEmpty() ? "当前知识库没有相关结果" : "检索完成");
                    if (!results.isEmpty()) resultList.setSelectedIndex(0);
                    else setPreview(null);
                } catch (Exception failure) {
                    resultSummary.setText("检索失败");
                    statusLabel.setText(rootCause(failure).getMessage());
                }
            }
        };
        searchWorker.execute();
    }

    private void setPreview(SearchResult result) {
        if (highlightWorker != null) highlightWorker.cancel(true);
        boolean present = result != null;
        openButton.setEnabled(present);
        locateButton.setEnabled(present);
        copyButton.setEnabled(present);
        previewArea.getHighlighter().removeAllHighlights();
        if (!present) {
            previewTitle.setText("选择一个结果");
            previewMeta.setText(" ");
            previewArea.setText("");
            lineNumbers.setText("");
            return;
        }
        DocumentChunk chunk = result.chunk();
        previewTitle.setText(chunk.fileName());
        previewTitle.setToolTipText(chunk.path());
        previewMeta.setText(chunk.path() + "  ·  L" + chunk.startLine() + "-" + chunk.endLine());
        previewMeta.setToolTipText(chunk.path());
        previewArea.setText(chunk.content());
        previewArea.setCaretPosition(0);
        StringBuilder numbers = new StringBuilder();
        for (int line = chunk.startLine(); line <= chunk.endLine(); line++) numbers.append(line).append('\n');
        lineNumbers.setText(numbers.toString());
        highlightQuery();
        locateSemanticMatches(result);
    }

    private void locateSemanticMatches(SearchResult result) {
        String query = searchField.getText().strip();
        DocumentChunk chunk = result.chunk();
        if (query.isEmpty() || !chunk.hasEmbedding() || !service.semanticEnabled()) return;
        String baseMeta = chunk.path() + "  ·  L" + chunk.startLine() + "-" + chunk.endLine();
        previewMeta.setText(baseMeta + "  ·  定位语义片段...");
        highlightWorker = new SwingWorker<>() {
            @Override protected List<SemanticHighlight> doInBackground() throws Exception {
                return service.semanticHighlights(query, chunk, 2);
            }

            @Override protected void done() {
                SearchResult selected = resultList.getSelectedValue();
                if (isCancelled() || selected == null || !selected.chunk().id().equals(chunk.id())
                        || !query.equals(searchField.getText().strip())) return;
                try {
                    List<SemanticHighlight> highlights = get();
                    previewArea.getHighlighter().removeAllHighlights();
                    applySemanticHighlights(highlights);
                    highlightQuery();
                    if (highlights.isEmpty()) previewMeta.setText(baseMeta);
                    else {
                        double best = highlights.stream().mapToDouble(SemanticHighlight::similarity).max().orElse(0);
                        previewMeta.setText(baseMeta + "  ·  语义片段 " + Math.round(best * 100) + "%");
                        previewArea.setCaretPosition(highlights.get(0).startOffset());
                    }
                } catch (Exception failure) {
                    previewMeta.setText(baseMeta);
                }
            }
        };
        highlightWorker.execute();
    }

    private void applySemanticHighlights(List<SemanticHighlight> highlights) {
        Highlighter.HighlightPainter painter = new DefaultHighlighter.DefaultHighlightPainter(new Color(38, 104, 83));
        for (SemanticHighlight highlight : highlights) {
            try {
                previewArea.getHighlighter().addHighlight(highlight.startOffset(), highlight.endOffset(), painter);
            } catch (javax.swing.text.BadLocationException ignored) {
                // The selected document changed before the asynchronous locator returned.
            }
        }
    }

    private void highlightQuery() {
        String query = searchField.getText().toLowerCase(Locale.ROOT);
        String content = previewArea.getText().toLowerCase(Locale.ROOT);
        Matcher matcher = HIGHLIGHT_TERM.matcher(query);
        Highlighter.HighlightPainter painter = new DefaultHighlighter.DefaultHighlightPainter(new Color(89, 93, 52));
        int highlights = 0;
        while (matcher.find() && highlights < 80) {
            String term = matcher.group();
            int offset = 0;
            while ((offset = content.indexOf(term, offset)) >= 0 && highlights < 80) {
                try {
                    previewArea.getHighlighter().addHighlight(offset, offset + term.length(), painter);
                } catch (javax.swing.text.BadLocationException ignored) {
                    break;
                }
                offset += term.length();
                highlights++;
            }
        }
    }

    private void loadApiConfig() {
        ApiConfig config = service.apiConfig();
        apiUrlField.setText(config.baseUrl());
        apiKeyField.setText(config.apiKey());
        if (!config.model().isBlank()) apiModelCombo.addItem(config.model());
        apiModelCombo.setSelectedItem(config.model());
        apiStatusLabel.setText(config.model().isBlank() ? "填写兼容 OpenAI 的 API 地址后获取模型"
                : "已保存模型：" + config.model());
    }

    private ApiConfig apiConfigFromFields() {
        Object model = apiModelCombo.isEditable() ? apiModelCombo.getEditor().getItem()
                : apiModelCombo.getSelectedItem();
        return new ApiConfig(apiUrlField.getText(), new String(apiKeyField.getPassword()),
                model == null ? "" : model.toString());
    }

    private void saveApiConfig() {
        try {
            ApiConfig config = apiConfigFromFields();
            service.saveApiConfig(config);
            apiStatusLabel.setForeground(Theme.ACCENT);
            apiStatusLabel.setText("API 配置已安全保存到本机");
        } catch (RuntimeException failure) {
            showError("无法保存 API 配置", failure);
        }
    }

    private void fetchModels(JButton button) {
        ApiConfig config = apiConfigFromFields();
        button.setEnabled(false);
        apiStatusLabel.setForeground(Theme.MUTED);
        apiStatusLabel.setText("正在连接 API 并获取模型...");
        new SwingWorker<List<String>, Void>() {
            @Override protected List<String> doInBackground() throws Exception {
                return service.fetchModels(config);
            }

            @Override protected void done() {
                button.setEnabled(true);
                try {
                    List<String> models = get();
                    Object previous = apiModelCombo.getEditor().getItem();
                    apiModelCombo.removeAllItems();
                    models.forEach(apiModelCombo::addItem);
                    if (previous != null && models.contains(previous.toString())) apiModelCombo.setSelectedItem(previous);
                    else if (!models.isEmpty()) apiModelCombo.setSelectedIndex(0);
                    apiStatusLabel.setForeground(Theme.ACCENT);
                    apiStatusLabel.setText(models.isEmpty() ? "API 未返回可用模型" : "已获取 " + models.size() + " 个模型");
                } catch (Exception failure) {
                    apiStatusLabel.setForeground(Theme.RED);
                    apiStatusLabel.setText(rootCause(failure).getMessage());
                }
            }
        }.execute();
    }

    private void askQuestion() {
        String question = questionArea.getText().strip();
        if (question.isEmpty()) return;
        ApiConfig config = apiConfigFromFields();
        try {
            config.validateForChat();
            service.saveApiConfig(config);
        } catch (RuntimeException failure) {
            showError("API 配置不完整", failure);
            return;
        }
        askButton.setEnabled(false);
        answerTitle.setText("正在检索并生成回答...");
        answerArea.setText("");
        citationModel.clear();
        new SwingWorker<RagAnswer, Void>() {
            @Override protected RagAnswer doInBackground() throws Exception {
                return service.ask(question, config);
            }

            @Override protected void done() {
                askButton.setEnabled(true);
                try {
                    RagAnswer answer = get();
                    answerTitle.setText("回答 · " + answer.model());
                    answerArea.setText(answer.text());
                    answerArea.setCaretPosition(0);
                    answer.citations().forEach(citationModel::addElement);
                    statusLabel.setText("问答完成，引用 " + answer.citations().size() + " 个片段");
                } catch (Exception failure) {
                    answerTitle.setText("生成失败");
                    answerArea.setText(rootCause(failure).getMessage());
                    statusLabel.setText("问答失败");
                }
            }
        }.execute();
    }

    private void setIndexing(boolean indexing, String status) {
        rebuildButton.setEnabled(!indexing);
        knowledgeBaseList.setEnabled(!indexing);
        progressBar.setVisible(indexing);
        progressBar.setIndeterminate(indexing);
        statusLabel.setText(status);
    }

    private void clearWorkspace() {
        resultModel.clear();
        citationModel.clear();
        searchField.setText("");
        answerArea.setText("当前知识库已切换，可以开始新的问答。");
        setPreview(null);
    }

    private void openSelectedFile() {
        SearchResult selected = resultList.getSelectedValue();
        if (selected != null) openFile(selected.chunk().filePath());
    }

    private void openSelectedDirectory() {
        SearchResult selected = resultList.getSelectedValue();
        if (selected != null) openFile(selected.chunk().filePath().getParent());
    }

    private void openCitation() {
        RagCitation selected = citationList.getSelectedValue();
        if (selected != null) openFile(selected.chunk().filePath());
    }

    private void openFile(Path path) {
        try {
            Desktop.getDesktop().open(path.toFile());
        } catch (IOException | UnsupportedOperationException failure) {
            showError("无法打开文件", failure);
        }
    }

    private void copySelectedChunk() {
        SearchResult selected = resultList.getSelectedValue();
        if (selected != null) {
            Toolkit.getDefaultToolkit().getSystemClipboard()
                    .setContents(new StringSelection(selected.chunk().content()), null);
            statusLabel.setText("片段已复制");
        }
    }

    private void scheduleSearch() {
        searchTimer.restart();
    }

    private KnowledgeBaseInput showKnowledgeBaseDialog(String title, String name, String description) {
        JTextField nameField = new JTextField(name, 28);
        JTextArea descriptionField = new JTextArea(description, 4, 28);
        descriptionField.setLineWrap(true);
        descriptionField.setWrapStyleWord(true);
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        JLabel nameLabel = new JLabel("名称");
        nameLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        nameField.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel descriptionLabel = new JLabel("描述");
        descriptionLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        JScrollPane descriptionScroll = scroll(descriptionField);
        descriptionScroll.setPreferredSize(new Dimension(380, 90));
        descriptionScroll.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(nameLabel);
        panel.add(Box.createVerticalStrut(5));
        panel.add(nameField);
        panel.add(Box.createVerticalStrut(12));
        panel.add(descriptionLabel);
        panel.add(Box.createVerticalStrut(5));
        panel.add(descriptionScroll);
        int result = JOptionPane.showConfirmDialog(this, panel, title,
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        return result == JOptionPane.OK_OPTION
                ? new KnowledgeBaseInput(nameField.getText(), descriptionField.getText()) : null;
    }

    private JPanel sidebarHeading(String title, String action, java.awt.event.ActionListener listener) {
        JPanel heading = new JPanel(new BorderLayout());
        heading.setOpaque(false);
        heading.add(sectionTitle(title), BorderLayout.WEST);
        JButton button = compactButton(action, false);
        button.addActionListener(listener);
        heading.add(button, BorderLayout.EAST);
        return heading;
    }

    private static JPanel labeledControl(String label, Component component) {
        JPanel panel = new JPanel(new BorderLayout(0, 4));
        panel.setOpaque(false);
        JLabel caption = new JLabel(label);
        caption.setForeground(Theme.MUTED);
        caption.setFont(Theme.UI_FONT.deriveFont(Font.BOLD, 9f));
        component.setPreferredSize(new Dimension(component.getPreferredSize().width, 34));
        panel.add(caption, BorderLayout.NORTH);
        panel.add(component, BorderLayout.CENTER);
        return panel;
    }

    private static JButton compactButton(String text, boolean primary) {
        JButton button = new JButton(text);
        Theme.styleButton(button, primary);
        button.setMargin(new Insets(6, 10, 6, 10));
        return button;
    }

    private static void styleModeButton(JButton button) {
        Theme.styleButton(button, false);
        button.setMargin(new Insets(8, 16, 8, 16));
    }

    private static JScrollPane scroll(Component view) {
        JScrollPane scroll = new JScrollPane(view);
        scroll.setBorder(null);
        scroll.getViewport().setBackground(Theme.BACKGROUND);
        scroll.getVerticalScrollBar().setUnitIncrement(18);
        return scroll;
    }

    private static JLabel sectionTitle(String text) {
        JLabel title = new JLabel(text);
        title.setForeground(Theme.TEXT);
        title.setFont(Theme.UI_FONT.deriveFont(Font.BOLD, 13f));
        return title;
    }

    private static Throwable rootCause(Throwable value) {
        Throwable result = value;
        while (result.getCause() != null) result = result.getCause();
        return result;
    }

    private void showError(String title, Throwable failure) {
        String message = rootCause(failure).getMessage();
        JOptionPane.showMessageDialog(this, message == null ? failure.toString() : message,
                title, JOptionPane.ERROR_MESSAGE);
    }

    private static String snippet(String content) {
        String compact = content.replaceAll("\\s+", " ").strip();
        return compact.length() <= 135 ? compact : compact.substring(0, 132) + "...";
    }

    private record KnowledgeBaseInput(String name, String description) {
    }

    private static final class PromptTextField extends JTextField {
        private final String prompt;

        private PromptTextField(String prompt) {
            this.prompt = prompt;
        }

        @Override protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            if (getText().isEmpty() && !isFocusOwner()) {
                graphics.setColor(Theme.MUTED);
                graphics.setFont(getFont());
                Insets insets = getInsets();
                graphics.drawString(prompt, insets.left,
                        (getHeight() + graphics.getFontMetrics().getAscent() - graphics.getFontMetrics().getDescent()) / 2);
            }
        }
    }

    private static final class KnowledgeBaseRenderer extends JPanel
            implements javax.swing.ListCellRenderer<KnowledgeBase> {
        private final JLabel name = new JLabel();
        private final JLabel description = new JLabel();

        private KnowledgeBaseRenderer() {
            super(new BorderLayout(0, 3));
            name.setFont(Theme.UI_FONT.deriveFont(Font.BOLD, 12f));
            description.setFont(Theme.UI_FONT.deriveFont(9f));
            add(name, BorderLayout.CENTER);
            add(description, BorderLayout.SOUTH);
        }

        @Override public Component getListCellRendererComponent(JList<? extends KnowledgeBase> list,
                KnowledgeBase value, int index, boolean selected, boolean focused) {
            setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(0, selected ? 3 : 0, 0, 0, Theme.ACCENT),
                    Theme.padding(8, selected ? 9 : 12, 8, 8)));
            setBackground(selected ? Theme.HOVER : Theme.PANEL);
            name.setText(value.name());
            name.setForeground(Theme.TEXT);
            description.setText(value.description().isBlank() ? "本地知识库" : value.description());
            description.setForeground(Theme.MUTED);
            return this;
        }
    }

    private static final class SourceRenderer extends DefaultListCellRenderer {
        @Override public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                                 boolean selected, boolean focused) {
            JLabel label = (JLabel) super.getListCellRendererComponent(list, value, index, selected, focused);
            Path path = (Path) value;
            label.setText(path.getFileName() == null ? path.toString() : path.getFileName().toString());
            label.setToolTipText(path.toString());
            label.setBorder(Theme.padding(4, 9, 4, 9));
            label.setBackground(selected ? Theme.HOVER : Theme.PANEL);
            label.setForeground(Theme.TEXT);
            return label;
        }
    }

    private static final class ResultRenderer extends JPanel implements javax.swing.ListCellRenderer<SearchResult> {
        private final JLabel title = new JLabel();
        private final JLabel score = new JLabel();
        private final JLabel meta = new JLabel();
        private final JTextArea excerpt = new JTextArea();

        private ResultRenderer() {
            super(new BorderLayout(8, 4));
            JPanel top = new JPanel(new BorderLayout());
            top.setOpaque(false);
            title.setFont(Theme.UI_FONT.deriveFont(Font.BOLD, 12f));
            score.setFont(Theme.UI_FONT.deriveFont(Font.BOLD, 10f));
            top.add(title, BorderLayout.CENTER);
            top.add(score, BorderLayout.EAST);
            excerpt.setEditable(false);
            excerpt.setLineWrap(true);
            excerpt.setWrapStyleWord(true);
            excerpt.setRows(2);
            excerpt.setFont(Theme.UI_FONT.deriveFont(10.5f));
            excerpt.setBorder(null);
            meta.setFont(Theme.UI_FONT.deriveFont(9f));
            add(top, BorderLayout.NORTH);
            add(excerpt, BorderLayout.CENTER);
            add(meta, BorderLayout.SOUTH);
        }

        @Override public Component getListCellRendererComponent(JList<? extends SearchResult> list, SearchResult value,
                                                                 int index, boolean selected, boolean focused) {
            Color background = selected ? Theme.PANEL_ALT : Theme.BACKGROUND;
            setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(0, selected ? 3 : 0, 1, 0,
                            selected ? Theme.ACCENT : Theme.BORDER),
                    Theme.padding(9, selected ? 8 : 11, 9, 7)));
            setBackground(background);
            excerpt.setBackground(background);
            title.setText(value.chunk().fileName());
            title.setForeground(Theme.TEXT);
            score.setText(Math.round(value.score() * 100) + "%");
            score.setForeground(value.score() >= 0.45 ? Theme.ACCENT : Theme.AMBER);
            excerpt.setText(snippet(value.chunk().content()));
            excerpt.setForeground(new Color(191, 200, 205));
            meta.setText("L" + value.chunk().startLine() + "-" + value.chunk().endLine() + "  ·  " + value.reason());
            meta.setForeground(Theme.MUTED);
            return this;
        }
    }

    private static final class CitationRenderer extends JPanel implements javax.swing.ListCellRenderer<RagCitation> {
        private final JLabel title = new JLabel();
        private final JLabel meta = new JLabel();

        private CitationRenderer() {
            super(new BorderLayout(0, 4));
            title.setFont(Theme.UI_FONT.deriveFont(Font.BOLD, 11f));
            meta.setFont(Theme.UI_FONT.deriveFont(9f));
            add(title, BorderLayout.CENTER);
            add(meta, BorderLayout.SOUTH);
        }

        @Override public Component getListCellRendererComponent(JList<? extends RagCitation> list, RagCitation value,
                                                                 int index, boolean selected, boolean focused) {
            setBackground(selected ? Theme.HOVER : Theme.PANEL);
            setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.BORDER), Theme.padding(8, 8, 8, 8)));
            title.setText("[" + value.number() + "] " + value.chunk().fileName());
            title.setForeground(Theme.TEXT);
            meta.setText("L" + value.chunk().startLine() + "-" + value.chunk().endLine()
                    + "  ·  " + Math.round(value.score() * 100) + "%");
            meta.setForeground(Theme.MUTED);
            setToolTipText(value.chunk().path());
            return this;
        }
    }
}
