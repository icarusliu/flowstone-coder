package com.liuqi.tool.idea.plugin;

import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.util.text.StringUtil;
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

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 实体类代码创建器
 *
 * @author LiuQi 2019/7/11-10:50
 * @version V1.0
 **/
public class GeneratorAction extends AbstractAnAction {
    private PsiDirectory workDir;
    private GeneratorConfig config;

    private static final Logger log = LoggerFactory.getLogger(GeneratorAction.class);

    @Override
    public synchronized void actionPerformed(@NotNull AnActionEvent anActionEvent) {
        PsiClass aClass = this.getEditingClass(anActionEvent);
        if (null == aClass) {
            return;
        }

        if (null == aClass.getAnnotation("com.baomidou.mybatisplus.annotation.TableName")) {
            // 只处理MyBatisPlus TableName注解的类
            this.showError("只能处理被TableName注解的Java类");
            return;
        }

        // 加载生成配置
        config = GeneratorConfig.load(project);
        if (CollectionUtils.isEmpty(config.getClasses())) {
            this.showError("config.yaml中未配置classes清单");
            return;
        }

        String entityName = aClass.getName().replace("Entity", "");

        if (StringUtils.isBlank(config.getBasePackage())) {
            this.showError("config.yaml中未配置basePackage");
            return;
        }

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

                log.info("准备生成类，名称：{}, 包：{}", name, cPackage);

                List<String> imports = Optional.ofNullable(definer.getImports())
                        .map(list -> list.stream().map(item -> item.replaceAll("\\$T\\$", entityName))
                                .collect(Collectors.toList())).orElse(null);

                ClassCreator.of(this.module)
                        .init(name, content)
                        .importClass(imports)
                        .copyFields(like)
                        .addTo(directory);
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
}
