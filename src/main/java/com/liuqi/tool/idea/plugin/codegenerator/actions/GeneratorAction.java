package com.liuqi.tool.idea.plugin.codegenerator.actions;

import com.intellij.ide.highlighter.HtmlFileType;
import com.intellij.lang.xml.XMLLanguage;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.fileTypes.UnknownFileType;
import com.intellij.psi.*;
import com.intellij.psi.xml.XmlDocument;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlTag;
import com.liuqi.tool.idea.plugin.codegenerator.bean.ClassDefiner;
import com.liuqi.tool.idea.plugin.codegenerator.bean.GeneratorConfig;
import com.liuqi.tool.idea.plugin.codegenerator.utils.ClassCreator;
import com.liuqi.tool.idea.plugin.codegenerator.utils.MyStringUtils;
import com.liuqi.tool.idea.plugin.codegenerator.utils.PsiUtils;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 实体类代码创建器
 *
 * @author LiuQi 2019/7/11-10:50
 * @version V1.0
 **/
public class GeneratorAction extends AbstractAction {
    /**
     * 工作目录
     */
    private PsiDirectory workDir;

    /**
     * 生成主配置
     */
    private GeneratorConfig config;

    private final Pattern loopFieldsContentPattern = Pattern.compile("(?<=\\$\\$\\$loopFields).*?(?=\\$\\$\\$)");

    private static final Logger log = LoggerFactory.getLogger(GeneratorAction.class);

    private final List<String> systemFields = Arrays.asList("id", "createTime", "createBy", "createUser", "updateTime", "updateBy");

    @Override
    public synchronized void actionPerformed(@NotNull AnActionEvent anActionEvent) {
        PsiClass aClass = this.getEditingClass(anActionEvent);
        if (null == aClass) {
            return;
        }

        // 加载生成配置
        config = GeneratorConfig.load(project);
        if (CollectionUtils.isEmpty(config.getClasses())) {
            this.showError("config.yaml中未配置classes清单");
            return;
        }

        // 如果有预期的注解，那么不包含该注解的类将不做处理，避免处理错误
        String expectAnnotation = config.getTableAnnotation();
        if (StringUtils.isNotBlank(expectAnnotation) && null == aClass.getAnnotation(expectAnnotation)) {
            this.showError("只能处理被" + expectAnnotation + "注解的Java类");
            return;
        }

        String entityName = aClass.getName().replace("Entity", "");

        if (StringUtils.isBlank(config.getBasePackage())) {
            // 未配置basePackage，则取当前Entity类往上两层当成basePackage;
            String packageName = psiUtils.getPackageName(aClass);
            String[] arr = packageName.split("\\.");
            StringBuilder sb = new StringBuilder();
            if (arr.length <= 2) {
                this.showError("未配置basePackage");
                return;
            }
            for (int i = 0; i < arr.length - 2; i++) {
                sb.append(arr[i]);
                if (i != arr.length - 3) {
                    sb.append(".");
                }
            }
            config.setBasePackage(sb.toString());
        }

        // 获取对应的实体中文名称
        String comment = "";
        if (StringUtils.isNotBlank(config.getCommentAnnotation())) {
            PsiAnnotation commentAnnotation = aClass.getAnnotation(config.getCommentAnnotation());
            comment = psiUtils.getAnnotationValue(commentAnnotation, "value")
                    .orElse("")
                    .replaceAll("\"", "");
        }
        String pComment = comment;

        // 获取工作目录，即main/java这个目录
        workDir = this.getWorkDir(aClass);

        String path = MyStringUtils.toUnderLineStr(entityName).replaceAll("_", "-");

        // 根据配置的类列表进行类的生成
        config.getClasses().forEach(clazz -> {
            ClassDefiner definer = PsiUtils.loadClassDefiner(project, clazz);
            String cPackage = config.getBasePackage();
            if (StringUtils.isNotBlank(definer.getDir())) {
                cPackage = cPackage + "." + definer.getDir().replaceAll("/", ".");
                // 获取类所在目录，如果不存在则进行创建
                PsiDirectory directory = PsiUtils.of(this.module)
                        .getOrCreateSubDirectory(workDir, cPackage);

                String name = definer.getName().replace("$T$", entityName);
                String content = definer.getTemplate().replace("$T$", entityName)
                        .replaceAll("\\$PATH\\$", path)
                        .replaceAll("\\$COMMENT\\$", pComment);
                String like = Optional.ofNullable(definer.getLike())
                        .map(str -> str.replaceAll("\\$T\\$", entityName))
                        .orElse(null);

                // 增加注释
                content = this.getComment(pComment + Optional.ofNullable(definer.getComment()).orElse("")) + content;

                log.info("准备生成类，名称：{}, 包：{}", name, cPackage);

                List<String> imports = Optional.ofNullable(definer.getImports())
                        .map(list -> list.stream().map(item -> item.replaceAll("\\$T\\$", entityName))
                                .collect(Collectors.toList())).orElse(null);

                // 创建类并添加到模块中
                ClassCreator.of(this.module)
                        .init(name, content)
                        .importClass(imports)
                        .copyFields(like)
                        .addTo(directory);
            } else {
                this.showError(clazz + "未配置所在包名");
            }
        });

        // 生成liquibase建表语句
        this.generateLiquibase(config, aClass);

        // 生成前端界面
        this.generatePage(config, pComment, entityName, path, aClass);
    }

    /**
     * 生成前端界面
     *
     * @param config 生成配置
     */
    private void generatePage(GeneratorConfig config, String comment, String entityName, String path, PsiClass aClass) {
        // 加载前端生成配置文件
        String pageTemplateFile = config.getPageTemplate();
        if (StringUtils.isBlank(pageTemplateFile)) {
            return;
        }

        ClassDefiner definer = PsiUtils.loadClassDefiner(project, pageTemplateFile);
        String template = definer.getTemplate();
        if (StringUtils.isBlank(template)) {
            return;
        }

        String dir = Optional.ofNullable(definer.getDir()).orElse("resources/vue");
        entityName = entityName.substring(0, 1).toLowerCase(Locale.ROOT) + entityName.substring(1);
        String filePath = dir + "/" + entityName + ".vue";
        if (null != psiUtils.getResourceFile(filePath)) {
            return;
        }

        template = template.replaceAll("\\$COMMENT\\$", comment)
                .replaceAll("\\$PATH\\$", path);

        // 替换$$$loopFields内容，先获取其中内容，再根据字段列表进行替换处理
        List<MatchResult> results = loopFieldsContentPattern.matcher(template).results().toList();
        if (CollectionUtils.isNotEmpty(results)) {
            // 只处理第一个
            String group = results.get(0).group();
            StringBuilder sb = new StringBuilder("\n\t");
            PsiField @NotNull [] allFields = aClass.getAllFields();
            for (PsiField field : allFields) {
                if (systemFields.contains(field.getName())) {
                    // 系统字段不做处理
                    continue;
                }

                String name = psiUtils.getAnnotationValue(field.getAnnotation(config.getCommentAnnotation()), "value")
                        .orElse("");

                sb.append(
                        group.replace("$FIELD_NAME$", name)
                                .replace("$FIELD_PROP$", field.getName())
                ).append("\n\t");
            }

            template = template.replaceAll("\\$\\$\\$loopFields.*\\$\\$\\$", sb.toString());
        }

        template = template.replaceAll("\\$BR\\$", "\n");

        // 保存内容
        String pTemplate = template;
        WriteCommandAction.runWriteCommandAction(project, () -> {
            PsiFile psiDir = psiUtils.getOrCreateResourceFile(filePath);
            PsiFile resultFile = PsiFileFactory.getInstance(this.project)
                    .createFileFromText(filePath, HtmlFileType.INSTANCE, pTemplate);
            psiUtils.format(resultFile);
            psiDir.add(resultFile);
        });
    }


    /**
     * 生成Liquibase建表语句
     */
    private void generateLiquibase(GeneratorConfig config, PsiClass aClass) {
        if (!config.getWithLiquibase()) {
            return;
        }

        // 根据字段生成建表语句，表名从Table或者TableName中获取
        String tableName = psiUtils.getAnnotationValue(aClass.getAnnotation(config.getTableAnnotation()), "value")
                .orElseGet(() -> {
                    String name = aClass.getName().replace("Entity", "");
                    return MyStringUtils.toUnderLineStr(name);
                });

        StringBuilder sb = new StringBuilder();
        sb.append("\ncreate table ")
                .append(tableName)
                .append("(\n");

        PsiField @NotNull [] allFields = aClass.getAllFields();

        // id特殊处理，并放在第一个
        PsiField idField = aClass.findFieldByName("id", true);
        if (null != idField) {
            PsiType type = idField.getType();
            if (type.getCanonicalText().contains("Long")) {
                sb.append("\tid bigint not null primary key auto_increment comment '主键',\n");
            } else {
                sb.append("\tid varchar(64) not null primary key comment '主键', \n");
            }
        }

        // 处理剩余字段
        for (int i = 0; i < allFields.length; i++) {
            PsiField field = allFields[i];
            String name = MyStringUtils.toUnderLineStr(field.getName());
            if (name.equals("id")) {
                continue;
            }

            PsiType type = field.getType();
            String typeName = type.getCanonicalText();
            if (typeName.contains(".")) {
                typeName = typeName.substring(typeName.lastIndexOf(".") + 1);
            }
            sb.append("\t").append(name).append(" ");
            switch (typeName) {
                case "Integer", "int", "Short", "Byte", "byte" -> sb.append("integer default 0");
                case "Long", "long" -> sb.append("bigint default 0");
                case "Float", "float", "Double", "double" -> sb.append("Numeric(24, 4) default 0");
                case "LocalDate", "LocalDateTime" -> {
                    sb.append("timestamp");
                    switch (name) {
                        case "update_time", "modify_time", "modify_at", "update_at" -> sb.append(" on update current_timestamp");
                        case "create_time", "create_at" -> sb.append(" default current_timestamp");
                    }
                }
                case "Boolean", "boolean" -> sb.append("int(1) default 0");
                default -> sb.append("varchar(255)");
            }

            // 获取备注信息
            psiUtils.getAnnotationValue(field.getAnnotation(config.getCommentAnnotation()), "value")
                    .ifPresent(comment -> sb.append(" comment '").append(comment).append("'"));

            if (i != allFields.length - 1) {
                sb.append(",\n");
            }
        }
        sb.append("\n)");

        // 写到liquibase文件中去
        String liquibaseFile = config.getLiquibaseFile();
        String fileName = liquibaseFile.substring(liquibaseFile.lastIndexOf(File.separator) + 1);
        PsiDirectory directory = psiUtils.getResourceDir(liquibaseFile);
        PsiFile psiFile = psiUtils.getOrCreateResourceFile(liquibaseFile);
        XmlFile file = (XmlFile) psiFile;
        XmlDocument document = file.getDocument();
        if (null == document) {
            // 无内容，需要增加内容
            String content = "<databaseChangeLog\n" +
                    "        xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n" +
                    "        xmlns=\"http://www.liquibase.org/xml/ns/dbchangelog\"\n" +
                    "        xsi:schemaLocation=\"http://www.liquibase.org/xml/ns/dbchangelog\n" +
                    "        http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-3.1.xsd\">";
            content += "<changeSet id=\"init-user\" author=\"test\">\n" +
                    "        <sql>";
            content += sb + "</sql></changeSet></databaseChangeLog>";
            PsiFile resultFile = PsiFileFactory.getInstance(this.project)
                    .createFileFromText(fileName, XMLLanguage.INSTANCE, content);
            psiUtils.format(resultFile);
            directory.add(resultFile);
        } else if (!psiFile.getText().contains(tableName)){
            XmlTag rootTag = document.getRootTag();
            XmlTag changeSet = rootTag.createChildTag("changeSet", null, null, false);
            changeSet.setAttribute("id", "create-table-" + tableName);
            changeSet.setAttribute("author", "codeGenerator");
            XmlTag sqlTag = changeSet.createChildTag("sql", null, sb.toString(), false);
//            XmlText text = XmlElementFactory.getInstance(project).createDisplayText(sb.toString());
//            sqlTag.add(text);
            sqlTag.getValue().setText(sb.toString());
            changeSet.add(sqlTag);
            WriteCommandAction.runWriteCommandAction(project, () -> {
                rootTag.add(changeSet);
                psiUtils.format(psiFile);
                psiFile.delete();
                directory.add(psiFile);
            });
        }
    }

    /**
     * 获取工作目录，即main/java目录
     */
    private PsiDirectory getWorkDir(PsiClass aClass) {
        PsiDirectory directory = aClass.getContainingFile().getContainingDirectory();
        while (!directory.getName().equals("java")) {
            directory = directory.getParentDirectory();
        }

        return directory;
    }

    /**
     * 组装类注释
     *
     * @param cName 中文名称
     * @return 类注释内容
     */
    private String getComment(String cName) {
        return "/** \n * " + cName + " \n * @author Coder Generator"
                + " " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")) + " \n**/\n";
    }
}
