package com.simplerag.ui;

import com.formdev.flatlaf.FlatDarkLaf;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.UIManager;
import javax.swing.border.Border;
import java.awt.Color;
import java.awt.Font;
import java.awt.Insets;

final class Theme {
    static final Color BACKGROUND = new Color(15, 18, 21);
    static final Color PANEL = new Color(21, 25, 29);
    static final Color PANEL_ALT = new Color(27, 32, 37);
    static final Color HOVER = new Color(38, 45, 50);
    static final Color BORDER = new Color(48, 56, 62);
    static final Color TEXT = new Color(235, 239, 241);
    static final Color MUTED = new Color(145, 156, 164);
    static final Color ACCENT = new Color(62, 201, 166);
    static final Color ACCENT_DARK = new Color(35, 134, 109);
    static final Color AMBER = new Color(238, 178, 85);
    static final Color RED = new Color(224, 105, 105);
    static final Font UI_FONT = new Font("Microsoft YaHei UI", Font.PLAIN, 13);
    static final Font MONO_FONT = new Font("JetBrains Mono", Font.PLAIN, 13);

    private Theme() {
    }

    static void install() {
        FlatDarkLaf.setup();
        UIManager.put("Label.font", UI_FONT);
        UIManager.put("Button.font", UI_FONT);
        UIManager.put("TextField.font", UI_FONT.deriveFont(14f));
        UIManager.put("ComboBox.font", UI_FONT);
        UIManager.put("List.font", UI_FONT);
        UIManager.put("Panel.background", BACKGROUND);
        UIManager.put("OptionPane.background", PANEL);
        UIManager.put("OptionPane.messageForeground", TEXT);
        UIManager.put("ToolTip.background", PANEL_ALT);
        UIManager.put("ToolTip.foreground", TEXT);
        UIManager.put("Component.arc", 6);
        UIManager.put("Button.arc", 6);
        UIManager.put("TextComponent.arc", 6);
        UIManager.put("ScrollBar.width", 11);
        UIManager.put("Component.focusColor", ACCENT_DARK);
        UIManager.put("Component.borderColor", BORDER);
        UIManager.put("TextField.background", BACKGROUND);
        UIManager.put("TextField.foreground", TEXT);
        UIManager.put("ComboBox.background", PANEL_ALT);
        UIManager.put("ComboBox.foreground", TEXT);
        UIManager.put("List.selectionBackground", HOVER);
        UIManager.put("List.selectionForeground", TEXT);
    }

    static Border padding(int top, int left, int bottom, int right) {
        return BorderFactory.createEmptyBorder(top, left, bottom, right);
    }

    static Border sectionBorder() {
        return BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 0, 1, BORDER), padding(18, 18, 18, 18));
    }

    static void styleButton(JButton button, boolean primary) {
        button.setOpaque(true);
        button.setFocusPainted(false);
        button.setBorderPainted(false);
        button.setForeground(primary ? new Color(9, 30, 25) : TEXT);
        button.setBackground(primary ? ACCENT : PANEL_ALT);
        button.setMargin(new Insets(9, 13, 9, 13));
        button.setCursor(new java.awt.Cursor(java.awt.Cursor.HAND_CURSOR));
    }

    static void opaque(JComponent component, Color color) {
        component.setOpaque(true);
        component.setBackground(color);
    }
}
