package com.simplerag.adapter.in.swing;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Font;

/** Owns and renders the window-wide operational status. */
public final class StatusBar extends JPanel {
    private final JLabel status = new JLabel("就绪");
    private final JLabel semantic = new JLabel("语义模型检查中");
    private final JLabel freshness = new JLabel("源文件检查中");

    public StatusBar() {
        super(new BorderLayout());
        Theme.opaque(this, Theme.PANEL);
        setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, Theme.BORDER), Theme.padding(6, 14, 6, 14)));
        status.setForeground(Theme.MUTED);
        status.setFont(Theme.UI_FONT.deriveFont(10f));
        semantic.setFont(Theme.UI_FONT.deriveFont(Font.BOLD, 10f));
        freshness.setFont(Theme.UI_FONT.deriveFont(Font.BOLD, 10f));
        freshness.setForeground(Theme.MUTED);
        JLabel local = new JLabel("LOCAL INDEX");
        local.setForeground(Theme.MUTED);
        local.setFont(Theme.UI_FONT.deriveFont(Font.BOLD, 9f));
        JPanel indicators = new JPanel(new FlowLayout(FlowLayout.RIGHT, 14, 0));
        indicators.setOpaque(false);
        indicators.add(freshness);
        indicators.add(semantic);
        indicators.add(local);
        add(status, BorderLayout.WEST);
        add(indicators, BorderLayout.EAST);
    }

    public void status(String text) { status.setText(text); }
    public void semantic(String text, boolean enabled) {
        semantic.setText(text);
        semantic.setForeground(enabled ? Theme.ACCENT : Theme.AMBER);
    }
    public void freshness(String text, boolean warning) {
        freshness.setText(text);
        freshness.setForeground(warning ? Theme.AMBER : Theme.MUTED);
    }
}
