package com.simplerag.adapter.in.swing;

import java.awt.Desktop;
import java.io.IOException;
import java.nio.file.Path;

public final class SystemDesktopFileGateway implements DesktopFileGateway {
    @Override
    public void open(Path path) throws IOException {
        if (!Desktop.isDesktopSupported()) throw new IOException("当前系统不支持桌面文件操作");
        Desktop.getDesktop().open(path.toFile());
    }
}
