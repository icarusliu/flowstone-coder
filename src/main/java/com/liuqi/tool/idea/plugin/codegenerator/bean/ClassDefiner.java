package com.liuqi.tool.idea.plugin.codegenerator.bean;

import java.util.List;

/**
 * 类生成配置
 */
public class ClassDefiner {
    private String name;

    private String dir;

    private String like;

    private List<String> imports;

    private String template;

    private String comment;

    public String getName() {
        return name;
    }

    public ClassDefiner setName(String name) {
        this.name = name;
        return this;
    }

    public String getDir() {
        return dir;
    }

    public ClassDefiner setDir(String dir) {
        this.dir = dir;
        return this;
    }

    public String getLike() {
        return like;
    }

    public ClassDefiner setLike(String like) {
        this.like = like;
        return this;
    }

    public List<String> getImports() {
        return imports;
    }

    public ClassDefiner setImports(List<String> imports) {
        this.imports = imports;
        return this;
    }

    public String getTemplate() {
        return template;
    }

    public ClassDefiner setTemplate(String template) {
        this.template = template;
        return this;
    }

    public String getComment() {
        return comment;
    }

    public ClassDefiner setComment(String comment) {
        this.comment = comment;
        return this;
    }
}
