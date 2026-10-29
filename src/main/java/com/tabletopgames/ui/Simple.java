package com.tabletopgames.ui;

import cn.nukkit.Player;
import cn.nukkit.form.element.ElementButton;
import cn.nukkit.form.handler.FormResponseHandler;
import cn.nukkit.form.window.FormWindowSimple;

import java.util.ArrayList;
import java.util.List;

/**
 * 简单表单封装（参考 PluginDemo 模板实现）。
 * 按钮与回调按加入顺序一一对应。
 */
public class Simple {

    private final FormWindowSimple form;
    private final List<Runnable> buttons = new ArrayList<>();
    private Runnable close;

    public Simple(String title, String content) {
        this.form = new FormWindowSimple(title, content);
    }

    public Simple add(String text, Runnable runnable) {
        this.buttons.add(runnable);
        this.form.addButton(new ElementButton(text));
        return this;
    }

    public Simple onClose(Runnable close) {
        this.close = close;
        return this;
    }

    public int buttonCount() {
        return this.buttons.size();
    }

    public void show(Player player) {
        this.form.addHandler(FormResponseHandler.withoutPlayer(ignored -> this.process()));
        player.showFormWindow(this.form);
    }

    private void process() {
        if (this.form.wasClosed()) {
            if (this.close != null) {
                this.close.run();
            }
            return;
        }
        int clicked = this.form.getResponse().getClickedButtonId();
        if (clicked >= 0 && clicked < this.buttons.size()) {
            this.buttons.get(clicked).run();
        }
    }
}