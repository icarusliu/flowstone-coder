package com.liuqi.tool.idea.plugin.codegenerator.bean;

import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiFile;
import com.intellij.psi.search.PsiShortNamesCache;
import org.apache.commons.collections.MapUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

import java.util.List;
import java.util.Map;

/**
 * 代码生成配置
 *
 * @author LiuQi 2020/4/21-8:58
 * @version V1.0
 **/
public class GeneratorConfig {
    private static final Logger log = LoggerFactory.getLogger(GeneratorConfig.class);

    /**
     * 从codeGenerator.properties中加载配置文件
     */
    public static GeneratorConfig load(Project project) {
        PsiFile[] files = PsiShortNamesCache.getInstance(project).getFilesByName("config.yaml");
        for (PsiFile file : files) {
            if (!file.getContainingDirectory().getName().equals("code-generator")) {
                continue;
            }

            Map<String, Object> obj = new Yaml().load(file.getText());
            log.debug("加载配置：{}", obj);

            GeneratorConfig config = new GeneratorConfig();
            config.setClasses((List<String>) obj.get("classes"))
                    .setBasePackage(MapUtils.getString(obj, "basePackage"))
                    .setTableAnnotation(MapUtils.getString(obj, "tableAnnotation"))
                    .setCommentAnnotation(MapUtils.getString(obj, "commentAnnotation"))
                    .setWithLiquibase(MapUtils.getBooleanValue(obj, "withLiquibase", false))
                    .setLiquibaseFile(MapUtils.getString(obj, "liquibaseFile", "liquibase/master.xml"));
            return config;
        }

        return new GeneratorConfig();
    }

    /**
     * 生成类所在包
     */
    private String basePackage;

    /**
     * 生成类列表
     */
    private List<String> classes;

    /**
     * 目标类预期的注解
     * 有这个注解的才会进行处理
     */
    private String tableAnnotation;

    /**
     * 注释注解，用于生成代码的类注释
     */
    private String commentAnnotation;

    /**
     * 是否包含liquibase
     */
    private Boolean withLiquibase = false;

    /**
     * withLiquibase为true时生效；liquibase的生成语句保存文件，不配置则默认是liquibase/master.xml
     */
    private String liquibaseFile = "liquibase/master.xml";

    public String getBasePackage() {
        return basePackage;
    }

    public GeneratorConfig setBasePackage(String basePackage) {
        this.basePackage = basePackage;
        return this;
    }

    public List<String> getClasses() {
        return classes;
    }

    public GeneratorConfig setClasses(List<String> classes) {
        this.classes = classes;
        return this;
    }

    public String getTableAnnotation() {
        return tableAnnotation;
    }

    public GeneratorConfig setTableAnnotation(String tableAnnotation) {
        this.tableAnnotation = tableAnnotation;
        return this;
    }

    public String getCommentAnnotation() {
        return commentAnnotation;
    }

    public GeneratorConfig setCommentAnnotation(String commentAnnotation) {
        this.commentAnnotation = commentAnnotation;
        return this;
    }

    public Boolean getWithLiquibase() {
        return withLiquibase;
    }

    public GeneratorConfig setWithLiquibase(Boolean withLiquibase) {
        this.withLiquibase = withLiquibase;
        return this;
    }

    public String getLiquibaseFile() {
        return liquibaseFile;
    }

    public GeneratorConfig setLiquibaseFile(String liquibaseFile) {
        this.liquibaseFile = liquibaseFile;
        return this;
    }
}
