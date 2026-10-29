package com.tabletopgames.ui;

import cn.nukkit.Player;
import cn.nukkit.form.element.ElementInput;
import cn.nukkit.form.element.ElementLabel;
import cn.nukkit.form.handler.FormResponseHandler;
import cn.nukkit.form.window.FormWindowCustom;

import java.util.function.Consumer;

/**
 * 自定义表单封装（参考 PluginDemo 模板实现），用于文本输入。
 * <p>
 * 仅支持单个输入框：{@link #input} 只记录第一个输入框的下标，提交时读取它。
 * 按钮为提交；玩家直接关闭窗口时走 {@link #onClose}。
 */
public class Custom {

    private final FormWindowCustom form;
    private int inputIndex = -1;
    private Consumer<String> submit;
    private Runnable close;

    public Custom(String title) {
        this.form = new FormWindowCustom(title);
    }

    public Custom label(String text) {
        form.addElement(new ElementLabel(text));
        return this;
    }

    public Custom input(String text, String placeholder) {
        if (inputIndex < 0) {
            inputIndex = form.getElements().size();
        }
        form.addElement(new ElementInput(text, placeholder));
        return this;
    }

    /** 带默认值的输入框：表单重开时能把上次填的内容原样带回。 */
    public Custom input(String text, String placeholder, String defaultValue) {
        if (inputIndex < 0) {
            inputIndex = form.getElements().size();
        }
        form.addElement(new ElementInput(text, placeholder, defaultValue));
        return this;
    }

    public Custom submit(String text) {
        form.setSubmitButtonText(text);
        return this;
    }

    public Custom onSubmit(Consumer<String> submit) {
        this.submit = submit;
        return this;
    }

    public Custom onClose(Runnable close) {
        this.close = close;
        return this;
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
        String value = inputIndex < 0 ? "" : this.form.getResponse().getInputResponse(inputIndex);
        if (this.submit != null) {
            this.submit.accept(value == null ? "" : value.trim());
        }
    }
}