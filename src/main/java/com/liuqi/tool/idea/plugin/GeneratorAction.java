package com.liuqi.tool.idea.plugin;

import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiAnnotationMemberValue;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiDirectory;
import com.liuqi.tool.idea.plugin.bean.ClassDefiner;
import com.liuqi.tool.idea.plugin.bean.GeneratorConfig;
import com.liuqi.tool.idea.plugin.utils.ClassCreator;
import com.liuqi.tool.idea.plugin.utils.MyStringUtils;
import com.liuqi.tool.idea.plugin.utils.PsiUtils;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

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
        String expectAnnotation = config.getExpectAnnotation();
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
