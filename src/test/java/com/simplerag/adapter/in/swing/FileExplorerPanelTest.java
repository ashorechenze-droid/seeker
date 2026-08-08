package com.simplerag.adapter.in.swing;

import com.simplerag.application.dto.FileIndexState;
import com.simplerag.application.dto.FileNodeView;
import org.junit.jupiter.api.Test;

import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.TreePath;
import java.awt.BorderLayout;
import java.awt.Component;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileExplorerPanelTest {
    private static final Path ROOT = Path.of("D:", "kb").toAbsolutePath();
    private static final Path SUB = ROOT.resolve("sub");

    private final List<Path> requested = new ArrayList<>();

    @Test
    void loadsFolderContentsOnlyWhenTheFolderIsExpanded() throws Exception {
        AtomicReference<FileExplorerPanel> panel = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            FileExplorerPanel created = build();
            created.reload();
            panel.set(created);
        });

        // A single root behaves like opening one folder: its own children load, the nested one waits.
        assertEquals(List.of(ROOT), requested);
        assertEquals(List.of("sub", "indexed.md", "other.md"), names(panel.get(), ROOT));

        SwingUtilities.invokeAndWait(() -> expand(panel.get(), SUB));

        assertEquals(List.of(ROOT, SUB), requested);
        assertEquals(List.of("nested.md"), names(panel.get(), SUB));
    }

    @Test
    void filterHidesNonMatchingFilesButKeepsFoldersReachable() throws Exception {
        AtomicReference<FileExplorerPanel> panel = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            FileExplorerPanel created = build();
            created.reload();
            filterField(created).setText("indexed");
            panel.set(created);
        });

        assertEquals(List.of("sub", "indexed.md"), names(panel.get(), ROOT));
    }

    @Test
    void reportsSelectedNodeAndNothingBeforeAnySelection() throws Exception {
        AtomicReference<FileExplorerPanel> panel = new AtomicReference<>();
        AtomicReference<FileNodeView> selected = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            FileExplorerPanel created = new FileExplorerPanel(new StubLoader(), new NoActions(), selected::set);
            created.reload();
            panel.set(created);
        });

        assertNull(panel.get().selected());

        SwingUtilities.invokeAndWait(() -> select(panel.get(), ROOT.resolve("indexed.md")));

        assertEquals("indexed.md", panel.get().selected().name());
        assertEquals(FileIndexState.INDEXED, panel.get().selected().state());
        assertEquals("indexed.md", selected.get().name());
    }

    @Test
    void rendersEachFileWithItsStateAndStrikesOutDeletedOnes() throws Exception {
        AtomicReference<FileExplorerPanel> panel = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            FileExplorerPanel created = build();
            created.reload();
            panel.set(created);
        });

        String indexed = render(panel.get(), ROOT.resolve("indexed.md"));
        String pending = render(panel.get(), ROOT.resolve("other.md"));
        String folder = render(panel.get(), SUB);

        assertTrue(indexed.contains("indexed.md"), indexed);
        assertTrue(indexed.contains("已索引 · 2"), indexed);
        assertTrue(pending.contains("未索引"), pending);
        assertTrue(folder.contains("sub"), folder);
    }

    private static String render(FileExplorerPanel panel, Path path) {
        DefaultMutableTreeNode node = node(panel, path);
        Component cell = panel.tree().getCellRenderer().getTreeCellRendererComponent(
                panel.tree(), node, false, false, node.isLeaf(), 0, false);
        return ((javax.swing.JLabel) cell).getText();
    }

    private FileExplorerPanel build() {
        return new FileExplorerPanel(new StubLoader(), new NoActions(), node -> { });
    }

    private static List<String> names(FileExplorerPanel panel, Path directory) {
        DefaultMutableTreeNode node = node(panel, directory);
        assertTrue(node != null, "expected a node for " + directory);
        List<String> names = new ArrayList<>();
        for (int index = 0; index < node.getChildCount(); index++) {
            names.add(String.valueOf(((DefaultMutableTreeNode) node.getChildAt(index)).getUserObject()));
        }
        return names;
    }

    private static void expand(FileExplorerPanel panel, Path directory) {
        panel.tree().expandPath(new TreePath(node(panel, directory).getPath()));
    }

    private static void select(FileExplorerPanel panel, Path path) {
        panel.tree().setSelectionPath(new TreePath(node(panel, path).getPath()));
    }

    private static DefaultMutableTreeNode node(FileExplorerPanel panel, Path path) {
        return search((DefaultMutableTreeNode) panel.tree().getModel().getRoot(), path);
    }

    private static DefaultMutableTreeNode search(DefaultMutableTreeNode parent, Path path) {
        for (int index = 0; index < parent.getChildCount(); index++) {
            DefaultMutableTreeNode child = (DefaultMutableTreeNode) parent.getChildAt(index);
            String name = String.valueOf(child.getUserObject());
            boolean matchesRoot = path.equals(ROOT) && name.equals(ROOT.toString());
            if (matchesRoot || name.equals(String.valueOf(path.getFileName()))) return child;
            DefaultMutableTreeNode nested = search(child, path);
            if (nested != null) return nested;
        }
        return null;
    }

    private static JTextField filterField(FileExplorerPanel panel) {
        Component header = ((BorderLayout) panel.getLayout()).getLayoutComponent(BorderLayout.NORTH);
        return (JTextField) ((BorderLayout) ((javax.swing.JPanel) header).getLayout())
                .getLayoutComponent(BorderLayout.SOUTH);
    }

    private final class StubLoader implements FileExplorerPanel.Loader {
        private final Map<Path, List<FileNodeView>> tree = new LinkedHashMap<>(Map.of(
                ROOT, List.of(folder(SUB), file("indexed.md", FileIndexState.INDEXED),
                        file("other.md", FileIndexState.NOT_INDEXED)),
                SUB, List.of(file("sub/nested.md", FileIndexState.MODIFIED))));

        @Override public void loadRoots(Consumer<List<FileNodeView>> onLoaded, Consumer<String> onFailed) {
            onLoaded.accept(List.of(new FileNodeView(ROOT, ROOT.toString(), true, FileIndexState.FOLDER,
                    0, 1, "", 0, 3, "", true)));
        }

        @Override public void loadChildren(Path directory, Consumer<List<FileNodeView>> onLoaded,
                                           Consumer<String> onFailed) {
            requested.add(directory);
            onLoaded.accept(tree.getOrDefault(directory, List.of()));
        }
    }

    private static FileNodeView folder(Path path) {
        return new FileNodeView(path, String.valueOf(path.getFileName()), true, FileIndexState.FOLDER,
                0, 1, "", 0, 1, "", false);
    }

    private static FileNodeView file(String relative, FileIndexState state) {
        Path path = ROOT.resolve(relative);
        return new FileNodeView(path, String.valueOf(path.getFileName()), false, state,
                120, 1, "plain-text", state == FileIndexState.INDEXED ? 2 : 0, 0, "hash", false);
    }

    private static final class NoActions implements FileExplorerPanel.Actions {
        @Override public void addFolder() { }
        @Override public void removeRoot(Path root) { }
        @Override public void openInApp(FileNodeView node) { }
        @Override public void openWithSystem(Path path) { }
        @Override public void revealInSystem(Path path) { }
        @Override public void copyPath(Path path) { }
    }
}
