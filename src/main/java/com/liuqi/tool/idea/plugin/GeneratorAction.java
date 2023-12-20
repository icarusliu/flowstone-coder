package com.liuqi.tool.idea.plugin;

import com.intellij.lang.xml.XMLLanguage;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.*;
import com.intellij.psi.xml.*;
import com.liuqi.tool.idea.plugin.bean.ClassDefiner;
import com.liuqi.tool.idea.plugin.bean.GeneratorConfig;
import com.liuqi.tool.idea.plugin.utils.ClassCreator;
import com.liuqi.tool.idea.plugin.utils.MyStringUtils;
import com.liuqi.tool.idea.plugin.utils.PsiUtils;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.xmlbeans.XmlLanguage;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import static com.intellij.psi.PsiType.BYTE;
import static com.intellij.psi.PsiTypes.*;

/**
 * 实体类代码创建器
 *
 * @author LiuQi 2019/7/11-10:50
 * @version V1.0
 **/
public class GeneratorAction extends AbstractAnAction {
    /**
     * 工作目录
     */
    private PsiDirectory workDir;

    /**
     * 生成主配置
     */
    private GeneratorConfig config;

    private static final Logger log = LoggerFactory.getLogger(GeneratorAction.class);

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
            this.showError("config.yaml中未配置basePackage");
            return;
        }

        String comment = "";
        if (StringUtils.isNotBlank(config.getCommentAnnotation())) {
            PsiAnnotation commentAnnotation = aClass.getAnnotation(config.getCommentAnnotation());
            comment = psiUtils.getAnnotationValue(commentAnnotation, "value")
                    .orElse("")
                    .replaceAll("\"", "");
        }
        String pComment = comment;

        // 获取当前实体所在目录的上两级目录，需要严格按说明中的目录组织，其它目录不考虑
        workDir = this.getWorkDir(aClass);

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
                        .replaceAll("\\$PATH\\$", MyStringUtils.toUnderLineStr(entityName).replaceAll("_", "-"));
                String like = Optional.ofNullable(definer.getLike())
                        .map(str -> str.replaceAll("\\$T\\$", entityName))
                        .orElse(null);

                // 增加注释
                content = this.getContent(pComment + Optional.ofNullable(definer.getComment()).orElse("")) + content;

                log.info("准备生成类，名称：{}, 包：{}", name, cPackage);

                List<String> imports = Optional.ofNullable(definer.getImports())
                        .map(list -> list.stream().map(item -> item.replaceAll("\\$T\\$", entityName))
                                .collect(Collectors.toList())).orElse(null);

                ClassCreator.of(this.module)
                        .init(name, content)
                        .importClass(imports)
                        .copyFields(like)
                        .addTo(directory);
            } else {
                this.showError(clazz + "未配置所在包名");
            }
        });

        // 根据是否生成liquibase来决定是否生成对应语句
        this.generateLiquibase(config, aClass);
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
        sb.append("create table ")
                .append(tableName)
                .append("(");

        PsiField @NotNull [] allFields = aClass.getAllFields();
        for (int i = 0; i < allFields.length; i++) {
            PsiField field = allFields[i];
            String name = field.getName();
            PsiType type = field.getType();
            sb.append(name).append(" ");
            if (type.equals(byteType())) {
                sb.append("bit default 0");
            } else if (type.equals(charType())) {
                sb.append("varchar(8)");
            } else if (type.equals(doubleType()) || type.equals(floatType())) {
                sb.append("numeric(24, 4)");
            } else if (type.equals(intType()) || type.equals(shortType())) {
                sb.append("integer");
            } else if (type.equals(longType())) {
                sb.append("bigint");
            } else {
                sb.append("varchar(255)");
            }

            if (name.equals("id")) {
                sb.append(" primary key");
            }

            if (i != allFields.length - 1) {
                sb.append(",");
            }
        }

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
            content +="<changeSet id=\"init-user\" author=\"test\">\n" +
                    "        <sql>";
            content += sb + "</sql></changeSet></databaseChangeLog>";
            PsiFile resultFile = PsiFileFactory.getInstance(this.project)
                    .createFileFromText(fileName, XMLLanguage.INSTANCE, content);
            psiUtils.format(resultFile);
            directory.add(resultFile);
        } else {
            XmlTag rootTag = document.getRootTag();
            XmlTag changeSet = rootTag.createChildTag("changeSet", null, null, false);
            changeSet.setAttribute("id", "create-table-" + tableName);
            changeSet.setAttribute("author", "codeGenerator");
            XmlTag sqlTag = changeSet.createChildTag("sql", null, null, false);
            XmlText text = XmlElementFactory.getInstance(project).createDisplayText(sb.toString());
            sqlTag.add(text);
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

    private String getContent(String cName) {
        return "/** " + cName + " \n * @author Coder Generator"
                + " " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")) + " **/\n";
    }
}
