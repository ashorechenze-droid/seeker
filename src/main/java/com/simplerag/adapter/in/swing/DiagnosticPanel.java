package com.simplerag.adapter.in.swing;

import com.simplerag.application.diagnostics.DiagnosticReportService;

import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Font;
import java.io.IOException;
import java.nio.file.Path;

public final class DiagnosticPanel extends JPanel {
    private final DiagnosticReportService controller;
    private final JTextArea report = new JTextArea();

    public DiagnosticPanel(DiagnosticReportService controller) {
        super(new BorderLayout(0, 10));
        this.controller = controller;
        Theme.opaque(this, Theme.BACKGROUND);
        setBorder(Theme.padding(18, 18, 18, 18));
        add(header(), BorderLayout.NORTH);
        report.setEditable(false);
        report.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        report.setBackground(Theme.PANEL);
        report.setForeground(Theme.TEXT);
        report.setCaretColor(Theme.TEXT);
        add(new JScrollPane(report), BorderLayout.CENTER);
        refresh();
    }

    public void refresh() { report.setText(controller.reportJson()); report.setCaretPosition(0); }

    private JPanel header() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setOpaque(false);
        JLabel title = new JLabel("诊断信息 · 仅包含状态和元数据，不含密钥、正文与 Prompt");
        title.setForeground(Theme.TEXT);
        title.setFont(Theme.UI_FONT.deriveFont(Font.BOLD, 14f));
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        actions.setOpaque(false);
        JButton refresh = new JButton("刷新");
        JButton export = new JButton("导出 JSON");
        Theme.styleButton(refresh, false); Theme.styleButton(export, true);
        refresh.addActionListener(event -> refresh());
        export.addActionListener(event -> export());
        actions.add(refresh); actions.add(export);
        panel.add(title, BorderLayout.CENTER); panel.add(actions, BorderLayout.EAST);
        return panel;
    }

    private void export() {
        JFileChooser chooser = new JFileChooser();
        chooser.setSelectedFile(Path.of("simplerag-diagnostics.json").toFile());
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        try {
            controller.export(chooser.getSelectedFile().toPath());
            JOptionPane.showMessageDialog(this, "诊断报告已导出", "导出完成", JOptionPane.INFORMATION_MESSAGE);
        } catch (IOException failure) {
            JOptionPane.showMessageDialog(this, failure.getMessage(), "导出失败", JOptionPane.ERROR_MESSAGE);
        }
    }
}
