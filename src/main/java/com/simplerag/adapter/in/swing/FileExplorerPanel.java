package com.simplerag.adapter.in.swing;

import com.simplerag.application.dto.FileIndexState;
import com.simplerag.application.dto.FileNodeView;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.JTree;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.event.TreeExpansionEvent;
import javax.swing.event.TreeWillExpandListener;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.ExpandVetoException;
import javax.swing.tree.TreePath;
import javax.swing.tree.TreeSelectionModel;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Insets;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Workspace file tree: the source directories of the active knowledge base, expanded on demand,
 * with each file marked by how the published index sees it.
 *
 * <p>Directory contents are fetched through {@link Loader} rather than read here, so no filesystem
 * call happens on the event dispatch thread.
 */
public final class FileExplorerPanel extends JPanel {
    /** Asynchronous data source; both methods must answer on the EDT. */
    public interface Loader {
        void loadRoots(Consumer<List<FileNodeView>> onLoaded, Consumer<String> onFailed);
        void loadChildren(Path directory, Consumer<List<FileNodeView>> onLoaded, Consumer<String> onFailed);
    }

    /** Commands the tree hands back to the workspace controller. */
    public interface Actions {
        void addFolder();
        void removeRoot(Path root);
        void openInApp(FileNodeView node);
        void openWithSystem(Path path);
        void revealInSystem(Path path);
        void copyPath(Path path);
    }

    private static final String LOADING = "载入中…";
    private static final String EMPTY = "（空目录）";

    private final DefaultMutableTreeNode invisibleRoot = new DefaultMutableTreeNode("workspace");
    private final DefaultTreeModel model = new DefaultTreeModel(invisibleRoot);
    private final JTree tree = new JTree(model);
    private final JTextField filter = new JTextField();
    private final JLabel status = new JLabel(" ");
    private final Loader loader;
    private final Actions actions;
    private final Consumer<FileNodeView> onSelect;
    private final Set<Path> pendingExpansion = new LinkedHashSet<>();

    public FileExplorerPanel(Loader loader, Actions actions, Consumer<FileNodeView> onSelect) {
        super(new BorderLayout(0, 8));
        this.loader = loader;
        this.actions = actions;
        this.onSelect = onSelect;
        setOpaque(false);
        add(buildHeader(), BorderLayout.NORTH);
        add(buildTree(), BorderLayout.CENTER);
        add(buildFooter(), BorderLayout.SOUTH);
    }

    /** Reloads roots, preserving which folders were open and which file was selected. */
    public void reload() {
        pendingExpansion.addAll(expandedPaths());
        FileNodeView selected = selected();
        Path keepSelected = selected == null ? null : selected.path();
        status.setText("正在读取目录…");
        loader.loadRoots(roots -> showRoots(roots, keepSelected), this::failed);
    }

    /** Drops every node; used when the active knowledge base changes. */
    public void clear() {
        pendingExpansion.clear();
        invisibleRoot.removeAllChildren();
        model.reload();
        status.setText(" ");
    }

    public FileNodeView selected() {
        TreePath path = tree.getSelectionPath();
        if (path == null) return null;
        Object node = path.getLastPathComponent();
        return node instanceof FileTreeNode file ? file.view : null;
    }

    /**
     * Package-private view of the tree for panel tests; the workspace controller drives this panel
     * through its public methods only.
     */
    JTree tree() {
        return tree;
    }

    /** Reloads a single folder in place, leaving the rest of the tree untouched. */
    public void refreshFolder(Path directory) {
        FileTreeNode node = find(invisibleRoot, directory);
        if (node == null || !node.view.directory()) { reload(); return; }
        node.loaded = null;
        node.loading = false;
        expand(node);
    }

    private void showRoots(List<FileNodeView> roots, Path keepSelected) {
        invisibleRoot.removeAllChildren();
        for (FileNodeView root : roots) invisibleRoot.add(newNode(root));
        model.reload();
        if (roots.isEmpty()) {
            status.setText("尚未添加数据源目录");
            notifySelection(null);
            return;
        }
        status.setText(roots.size() + " 个数据源目录");
        // A single root behaves like an editor opening one folder: show its contents immediately.
        if (pendingExpansion.isEmpty() && roots.size() == 1) {
            pendingExpansion.add(roots.get(0).path());
        }
        applyPendingExpansion(invisibleRoot);
        if (keepSelected != null) selectPath(keepSelected);
    }

    private void applyPendingExpansion(DefaultMutableTreeNode parent) {
        for (int index = 0; index < parent.getChildCount(); index++) {
            if (parent.getChildAt(index) instanceof FileTreeNode child
                    && child.view.directory() && pendingExpansion.contains(child.view.path())) {
                tree.expandPath(new TreePath(child.getPath()));
            }
        }
    }

    private void expand(FileTreeNode node) {
        if (node.loading || node.loaded != null) return;
        node.loading = true;
        node.removeAllChildren();
        node.add(new DefaultMutableTreeNode(LOADING));
        model.nodeStructureChanged(node);
        Path directory = node.view.path();
        loader.loadChildren(directory, children -> accept(node, children),
                failure -> { node.loading = false; placeholder(node, failure); });
    }

    private void accept(FileTreeNode node, List<FileNodeView> children) {
        node.loading = false;
        node.loaded = children;
        pendingExpansion.remove(node.view.path());
        rebuild(node);
        tree.expandPath(new TreePath(node.getPath()));
        applyPendingExpansion(node);
    }

    private void rebuild(FileTreeNode node) {
        // Reuse child nodes whose state is unchanged so already-expanded subtrees survive a
        // filter keystroke; a changed view means the folder's contents may differ and is rebuilt.
        Map<Path, FileTreeNode> existing = new HashMap<>();
        for (int index = 0; index < node.getChildCount(); index++) {
            if (node.getChildAt(index) instanceof FileTreeNode child) existing.put(child.view.path(), child);
        }
        node.removeAllChildren();
        String needle = filter.getText().strip().toLowerCase(Locale.ROOT);
        int hidden = 0;
        for (FileNodeView child : node.loaded) {
            // Folders always stay visible so a filtered name deeper in the tree is still reachable.
            if (!needle.isEmpty() && !child.directory()
                    && !child.name().toLowerCase(Locale.ROOT).contains(needle)) {
                hidden++;
                continue;
            }
            FileTreeNode reused = existing.get(child.path());
            node.add(reused != null && reused.view.equals(child) ? reused : newNode(child));
        }
        if (node.getChildCount() == 0) {
            node.add(new DefaultMutableTreeNode(hidden > 0 ? "（" + hidden + " 个文件被过滤）" : EMPTY));
        }
        model.nodeStructureChanged(node);
    }

    /** Reapplies the filter to every folder already loaded, keeping the open ones open. */
    private void refreshDisplay() {
        Set<Path> expanded = expandedPaths();
        rebuildRecursively(invisibleRoot);
        for (Path path : expanded) {
            FileTreeNode node = find(invisibleRoot, path);
            if (node != null) tree.expandPath(new TreePath(node.getPath()));
        }
    }

    private void rebuildRecursively(DefaultMutableTreeNode parent) {
        for (int index = 0; index < parent.getChildCount(); index++) {
            if (!(parent.getChildAt(index) instanceof FileTreeNode child)) continue;
            if (child.loaded != null) rebuild(child);
            rebuildRecursively(child);
        }
    }

    private void placeholder(FileTreeNode node, String message) {
        node.removeAllChildren();
        node.add(new DefaultMutableTreeNode(message));
        model.nodeStructureChanged(node);
    }

    private void failed(String message) {
        status.setText(message);
    }

    private FileTreeNode newNode(FileNodeView view) {
        FileTreeNode node = new FileTreeNode(view);
        if (view.directory()) node.add(new DefaultMutableTreeNode(LOADING));
        return node;
    }

    private void selectPath(Path path) {
        FileTreeNode node = find(invisibleRoot, path);
        if (node == null) return;
        TreePath treePath = new TreePath(node.getPath());
        tree.setSelectionPath(treePath);
        tree.scrollPathToVisible(treePath);
    }

    private static FileTreeNode find(DefaultMutableTreeNode parent, Path path) {
        for (int index = 0; index < parent.getChildCount(); index++) {
            if (!(parent.getChildAt(index) instanceof FileTreeNode child)) continue;
            if (child.view.path().equals(path)) return child;
            if (path.startsWith(child.view.path())) {
                FileTreeNode nested = find(child, path);
                if (nested != null) return nested;
            }
        }
        return null;
    }

    private Set<Path> expandedPaths() {
        Set<Path> expanded = new LinkedHashSet<>();
        Enumeration<TreePath> paths = tree.getExpandedDescendants(new TreePath(invisibleRoot));
        if (paths == null) return expanded;
        while (paths.hasMoreElements()) {
            Object last = paths.nextElement().getLastPathComponent();
            if (last instanceof FileTreeNode node) expanded.add(node.view.path());
        }
        return expanded;
    }

    private void notifySelection(FileNodeView view) {
        if (onSelect != null) onSelect.accept(view);
    }

    private JComponent buildHeader() {
        JPanel header = new JPanel(new BorderLayout(0, 7));
        header.setOpaque(false);
        JPanel title = new JPanel(new BorderLayout());
        title.setOpaque(false);
        JLabel label = new JLabel("资源管理器");
        label.setForeground(Theme.TEXT);
        label.setFont(Theme.UI_FONT.deriveFont(Font.BOLD, 12f));
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 5, 0));
        buttons.setOpaque(false);
        JButton open = compact("打开文件夹");
        open.setToolTipText("把一个本地目录加入当前知识库的数据源");
        open.addActionListener(event -> actions.addFolder());
        JButton refresh = compact("刷新");
        refresh.setToolTipText("重新比对磁盘与索引，更新每个文件的状态");
        refresh.addActionListener(event -> reload());
        buttons.add(open);
        buttons.add(refresh);
        title.add(label, BorderLayout.WEST);
        title.add(buttons, BorderLayout.EAST);
        filter.putClientProperty("JTextField.placeholderText", "按文件名过滤…");
        filter.setToolTipText("只过滤已展开目录中的文件；目录始终保留");
        filter.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent event) { refreshDisplay(); }
            public void removeUpdate(DocumentEvent event) { refreshDisplay(); }
            public void changedUpdate(DocumentEvent event) { refreshDisplay(); }
        });
        header.add(title, BorderLayout.NORTH);
        header.add(filter, BorderLayout.SOUTH);
        return header;
    }

    private JComponent buildTree() {
        tree.setRootVisible(false);
        tree.setShowsRootHandles(true);
        tree.setRowHeight(22);
        tree.setBackground(Theme.PANEL);
        tree.setCellRenderer(new NodeRenderer());
        tree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
        tree.addTreeWillExpandListener(new TreeWillExpandListener() {
            @Override public void treeWillExpand(TreeExpansionEvent event) throws ExpandVetoException {
                if (event.getPath().getLastPathComponent() instanceof FileTreeNode node) expand(node);
            }
            @Override public void treeWillCollapse(TreeExpansionEvent event) { }
        });
        tree.addTreeSelectionListener(event -> notifySelection(selected()));
        tree.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent event) { maybePopup(event); }
            @Override public void mouseReleased(MouseEvent event) { maybePopup(event); }
            @Override public void mouseClicked(MouseEvent event) {
                if (event.getClickCount() != 2 || event.getButton() != MouseEvent.BUTTON1) return;
                FileNodeView view = selected();
                if (view != null && !view.directory()) actions.openInApp(view);
            }
        });
        JScrollPane scroll = new JScrollPane(tree);
        scroll.setBorder(BorderFactory.createLineBorder(Theme.BORDER));
        scroll.getViewport().setBackground(Theme.PANEL);
        scroll.setPreferredSize(new Dimension(240, 260));
        return scroll;
    }

    private JComponent buildFooter() {
        JPanel footer = new JPanel(new BorderLayout(0, 5));
        footer.setOpaque(false);
        JPanel legend = new JPanel(new FlowLayout(FlowLayout.LEFT, 9, 0));
        legend.setOpaque(false);
        for (FileIndexState state : List.of(FileIndexState.INDEXED, FileIndexState.MODIFIED,
                FileIndexState.NOT_INDEXED, FileIndexState.DELETED, FileIndexState.UNSUPPORTED)) {
            JLabel item = new JLabel(FileStatusStyle.label(state), FileStatusStyle.dot(state), JLabel.LEFT);
            item.setFont(Theme.UI_FONT.deriveFont(9f));
            item.setForeground(Theme.MUTED);
            item.setIconTextGap(3);
            legend.add(item);
        }
        status.setForeground(Theme.MUTED);
        status.setFont(Theme.UI_FONT.deriveFont(10f));
        footer.add(legend, BorderLayout.NORTH);
        footer.add(status, BorderLayout.SOUTH);
        return footer;
    }

    private void maybePopup(MouseEvent event) {
        if (!event.isPopupTrigger()) return;
        TreePath path = tree.getPathForLocation(event.getX(), event.getY());
        if (path == null) return;
        tree.setSelectionPath(path);
        FileNodeView view = selected();
        if (view == null) return;
        JPopupMenu menu = new JPopupMenu();
        if (!view.directory()) {
            menu.add(item("在应用内打开", () -> actions.openInApp(view)));
        }
        menu.add(item("用系统默认程序打开", () -> actions.openWithSystem(view.path())));
        menu.add(item("打开所在文件夹", () -> actions.revealInSystem(
                view.directory() ? view.path() : view.path().getParent())));
        menu.add(item("复制路径", () -> actions.copyPath(view.path())));
        if (view.directory()) menu.add(item("刷新此文件夹", () -> refreshFolder(view.path())));
        if (view.root()) {
            menu.addSeparator();
            menu.add(item("从知识库移除此目录", () -> actions.removeRoot(view.path())));
        }
        menu.show(tree, event.getX(), event.getY());
    }

    private static JMenuItem item(String text, Runnable action) {
        JMenuItem menuItem = new JMenuItem(text);
        menuItem.addActionListener(event -> action.run());
        return menuItem;
    }

    private static JButton compact(String text) {
        JButton button = new JButton(text);
        Theme.styleButton(button, false);
        button.setMargin(new Insets(3, 7, 3, 7));
        button.setFont(Theme.UI_FONT.deriveFont(10f));
        return button;
    }

    private static final class FileTreeNode extends DefaultMutableTreeNode {
        private final transient FileNodeView view;
        private transient List<FileNodeView> loaded;
        private transient boolean loading;

        private FileTreeNode(FileNodeView view) {
            super(view.name(), view.directory());
            this.view = view;
        }
    }

    private static final class NodeRenderer extends DefaultTreeCellRenderer {
        @Override
        public Component getTreeCellRendererComponent(JTree source, Object value, boolean selected,
                                                      boolean expanded, boolean leaf, int row,
                                                      boolean focused) {
            super.getTreeCellRendererComponent(source, value, selected, expanded, leaf, row, focused);
            setBackgroundNonSelectionColor(Theme.PANEL);
            setBackgroundSelectionColor(Theme.HOVER);
            setBorderSelectionColor(Theme.HOVER);
            setFont(Theme.UI_FONT.deriveFont(11.5f));
            if (!(value instanceof FileTreeNode node)) {
                setIcon(null);
                setForeground(Theme.MUTED);
                setText("<html><i>" + escape(String.valueOf(value)) + "</i></html>");
                setToolTipText(null);
                return this;
            }
            FileNodeView view = node.view;
            setIcon(FileStatusStyle.icon(view));
            setForeground(Theme.TEXT);
            setText(render(view));
            setToolTipText("<html>" + escape(view.path().toString()) + "<br>"
                    + escape(FileStatusStyle.explanation(view)) + "</html>");
            return this;
        }

        private static String render(FileNodeView view) {
            String name = escape(view.root() ? rootLabel(view) : view.name());
            String body = view.state() == FileIndexState.DELETED ? "<s>" + name + "</s>" : name;
            if (view.state() == FileIndexState.IGNORED || view.state() == FileIndexState.UNSUPPORTED) {
                body = "<span style='color:" + FileStatusStyle.hex(Theme.MUTED) + "'>" + body + "</span>";
            }
            return "<html>" + body + trailer(view) + "</html>";
        }

        private static String trailer(FileNodeView view) {
            String text;
            if (view.directory()) {
                if (view.state() == FileIndexState.DELETED) text = "目录不可访问";
                else if (view.indexedDescendants() > 0) text = String.valueOf(view.indexedDescendants());
                else return "";
            } else if (view.state() == FileIndexState.INDEXED) {
                text = "已索引 · " + view.chunkCount();
            } else {
                text = FileStatusStyle.label(view.state());
            }
            String color = FileStatusStyle.hex(view.directory()
                    ? Theme.MUTED : FileStatusStyle.color(view.state()));
            return "  <span style='color:" + color + ";font-size:9px'>" + escape(text) + "</span>";
        }

        private static String rootLabel(FileNodeView view) {
            Path name = view.path().getFileName();
            return name == null ? view.path().toString() : name.toString();
        }

        private static String escape(String value) {
            return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        }
    }
}
