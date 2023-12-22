package com.liuqi.tool.idea.plugin.codegenerator.actions;

import com.intellij.codeInsight.hint.HintManager;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.FileIndexFacade;
import com.intellij.psi.*;
import com.liuqi.tool.idea.plugin.codegenerator.utils.PsiUtils;

/**
 * 基础Action
 *
 * @author  LiuQi 2019/12/13-19:44
 * @version V1.0
 **/
public abstract class AbstractAction extends AnAction {
    /**
     * 当前项目
     */
    protected Project project;

    /**
     * 当前目录
     */
    protected PsiDirectory containerDirectory;

    /**
     * 工具类
     */
    protected PsiUtils psiUtils;

    /**
     * 当前模块
     */
    protected Module module;

    protected  Editor editor;

    /**
     * 错误消息提示
     */
    protected void showError(String msg) {
        HintManager.getInstance().showErrorHint(this.editor, msg);
    }

    /**
     * 获取当前打开的类
     */
    protected PsiClass getEditingClass(AnActionEvent anActionEvent) {
        project = anActionEvent.getProject();

        if (null == project) {
            return null;
        }

        editor = anActionEvent.getData(CommonDataKeys.EDITOR);
        if (null == editor) {
            return null;
        }

        PsiFile psiFile = PsiDocumentManager.getInstance(project).getPsiFile(editor.getDocument());
        if (!(psiFile instanceof PsiJavaFile javaFile)) {
            // 不是Java类，不做处理
            this.showError("只能处理Java类");
            return null;
        }

        PsiClass[] classes = javaFile.getClasses();
        if (0 == classes.length) {
            this.showError("未打开任何Java类");
            return null;
        }

        containerDirectory = classes[0].getContainingFile().getContainingDirectory();

        // 获取当前模块
        module = FileIndexFacade.getInstance(project).getModuleForFile(classes[0].getContainingFile().getVirtualFile());
        psiUtils = PsiUtils.of(module);

        return classes[0];
    }
}
