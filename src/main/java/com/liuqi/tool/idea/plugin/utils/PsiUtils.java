package com.liuqi.tool.idea.plugin.utils;

import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.fileTypes.FileTypes;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.*;
import com.intellij.psi.codeStyle.CodeStyleManager;
import com.intellij.psi.codeStyle.JavaCodeStyleManager;
import com.intellij.psi.impl.file.PsiDirectoryFactory;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.PsiShortNamesCache;
import com.liuqi.tool.idea.plugin.bean.ClassDefiner;
import org.apache.commons.collections.MapUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.jps.model.java.JavaModuleSourceRootTypes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * PSI操作辅助类
 *
 * @author LiuQi 2019/7/12-10:22
 * @version V1.0
 **/
public class PsiUtils {
    private static final Logger log = LoggerFactory.getLogger(PsiUtils.class);

    private Project project;
    private Module module;

    private PsiUtils(Module module) {
        this.module = module;
        this.project = module.getProject();
    }

    public static PsiUtils of(Module module) {
        return new PsiUtils(module);
    }

    /**
     * 根据类名加载类生成配置
     */
    public static ClassDefiner loadClassDefiner(Project project, String className) {
        PsiFile[] files = PsiShortNamesCache.getInstance(project).getFilesByName(className + ".yaml");
        ClassDefiner definer = new ClassDefiner();
        for (PsiFile file : files) {
            if (!file.getContainingDirectory().getName().equals("code-generator")) {
                continue;
            }

            Map<String, Object> obj = new Yaml().load(file.getText());
            log.debug("加载类生成配置：{}", obj);

            definer.setDir(MapUtils.getString(obj, "dir"))
                    .setName(MapUtils.getString(obj, "name"))
                    .setImports((List<String>) obj.get("imports"))
                    .setLike(MapUtils.getString(obj, "like"))
                    .setTemplate(MapUtils.getString(obj, "template"))
                    .setComment(MapUtils.getString(obj, "comment"));

            return definer;
        }

        return definer;
    }

    /**
     * 引入类
     */
    public void importClass(PsiClass srcClass, PsiClass... toImportClasses) {
        for (PsiClass toImportClass : toImportClasses) {
            if (null == toImportClass) {
                continue;
            }

            ((PsiJavaFile) srcClass.getContainingFile()).importClass(toImportClass);
        }
    }

    /**
     * 在资源目录(resources）下创建文件
     */
    public void createResourceFile(String dirName, String fileName, String content) {
        // 获取目录，在resources目录下，如果没有这个目录，那么创建一个目录
        ModuleRootManager rootManager = ModuleRootManager.getInstance(module);
        List<VirtualFile> sourceRoots = rootManager.getSourceRoots(JavaModuleSourceRootTypes.RESOURCES);
        VirtualFile resourceDirectory = sourceRoots.get(0);
        VirtualFile dir = resourceDirectory.findChild(dirName);
        if (null == dir) {
            try {
                dir = resourceDirectory.createChildDirectory(this, dirName);
            } catch (IOException e) {
                e.printStackTrace();
                dir = resourceDirectory;
            }
        }

        PsiDirectory psiDirectory = PsiDirectoryFactory.getInstance(project).createDirectory(dir);

        PsiFile file = PsiFileFactory.getInstance(project).createFileFromText(fileName, FileTypes.PLAIN_TEXT,
                content);
        psiDirectory.add(file);
    }

    /**
     * 获取类所在包名
     */
    public String getPackageName(PsiClass psiClass) {
        return ((PsiJavaFile)psiClass.getContainingFile()).getPackageName();
    }

    /**
     * 获取注解属性值
     */
    public Optional<String> getAnnotationValue(PsiClass psiClass, String annotation, String field) {
        return Optional.ofNullable(psiClass.getAnnotation(annotation)).map(a -> {
            PsiAnnotationMemberValue value = a.findAttributeValue(field);
            if (null != value) {
                return Optional.of(value.getText());
            } else {
                return Optional.<String>empty();
            }
        }).orElse(Optional.empty());
    }

    /**
     * 获取注解属性值
     */
    public Optional<String> getAnnotationValue(PsiAnnotation annotation, String field) {
        return Optional.ofNullable(annotation).map(a -> {
            PsiAnnotationMemberValue value = a.findAttributeValue(field);
            if (null != value) {
                return Optional.of(value.getText().replaceAll("\"", ""));
            } else {
                return Optional.<String>empty();
            }
        }).orElse(Optional.empty());
    }

    /**
     * 获取注解属性值
     */
    public Optional<String> getAnnotationValue(PsiFile psiFile, String annotation, String field) {
        return getAnnotationValue(((PsiJavaFile)psiFile).getClasses()[0], annotation, field);
    }

    /**
     * 获取所在包及类名
     */
    public String getPackageAndName(PsiClass psiClass) {
        return ((PsiJavaFile)psiClass.getContainingFile()).getPackageName().concat(".").concat(psiClass.getName());
    }

    /**
     * 添加注解
     */
    public PsiAnnotation addAnnotation(PsiClass psiClass, String annotation) {
        PsiAnnotation psiAnnotation = Objects.requireNonNull(psiClass.getModifierList()).addAnnotation(annotation);
        JavaCodeStyleManager.getInstance(project).shortenClassReferences(psiAnnotation);
        return psiAnnotation;
    }

    /**
     * 为字段添加注解
     */
    public PsiAnnotation addAnnotation(PsiField field, String annotation) {
        PsiAnnotation psiAnnotation = field.getModifierList().addAnnotation(annotation);
        JavaCodeStyleManager.getInstance(project).shortenClassReferences(psiAnnotation);
        return psiAnnotation;
    }

    /**
     * 为类添加注解
     */
    public PsiElement addAnnotationFromStrAfter(PsiClass psiElement, String content, PsiElement posElement) {
        PsiAnnotation psiAnnotation = PsiElementFactory.getInstance(project).createAnnotationFromText(content, null);
        PsiModifierList psiModifierList = psiElement.getModifierList();
        PsiElement addResult = psiModifierList.addAfter(psiAnnotation, posElement);
        JavaCodeStyleManager.getInstance(project).shortenClassReferences(psiAnnotation);

        return addResult;
    }

    /**
     * 添加注解
     */
    public void addAnnotationFromStrAfter(PsiField psiElement, String content, PsiElement posElement) {
        PsiAnnotation psiAnnotation = PsiElementFactory.getInstance(project).createAnnotationFromText(content, null);
        PsiModifierList psiModifierList = psiElement.getModifierList();
        psiModifierList.addAfter(psiAnnotation, posElement);
        JavaCodeStyleManager.getInstance(project).shortenClassReferences(psiAnnotation);
    }

    /**
     * 添加注解
     */
    public void addAnnotationFromStrFirst(PsiField psiElement, String content) {
        PsiAnnotation psiAnnotation = PsiElementFactory.getInstance(project).createAnnotationFromText(content, null);
        PsiModifierList psiModifierList = psiElement.getModifierList();
        psiModifierList.addBefore(psiAnnotation, psiModifierList.getFirstChild());
        JavaCodeStyleManager.getInstance(project).shortenClassReferences(psiAnnotation);
    }

    /**
     * 格式化代码
     *
     * @param psiElement 需要格式化的文件
     */
    public void format(PsiElement psiElement) {
        CodeStyleManager.getInstance(project).reformat(psiElement);
    }

    /**
     * 查找类
     *
     * @param className 类名
     * @return 查找到的类
     */
    public Optional<PsiClass> findClass(String className) {
        return findClass(className, psiClass -> true);
    }

    /**
     * 根据条件查找类
     */
    public Optional<PsiClass> findClass(String className, Predicate<PsiClass> predicate) {
        PsiShortNamesCache shortNamesCache = PsiShortNamesCache.getInstance(project);

        int idx = className.lastIndexOf(".");
        if (-1 != idx) {
            String packageName = className.substring(0, idx);
            String name = className.substring(idx + 1);
            PsiClass[] classes = shortNamesCache.getClassesByName(name, GlobalSearchScope.allScope(project));

            for (PsiClass aClass : classes) {
                PsiJavaFile javaFile = (PsiJavaFile) aClass.getContainingFile();
                if (javaFile.getPackageName().equals(packageName) && predicate.test(aClass)) {
                    return Optional.of(aClass);
                }
            }
        } else {
            PsiClass[] classes = shortNamesCache.getClassesByName(className, GlobalSearchScope.allScope(project));
            for (PsiClass aClass : classes) {
                if (predicate.test(aClass)) {
                    return Optional.ofNullable(aClass);
                }
            }
        }

        return Optional.empty();
    }

    /**
     * 获取或者创建子目录
     *
     * @param parentDirectory  父级目录
     * @param subDirectoryName 子目录名称
     * @return 查找到的或者创建的子目录名称
     */
    public PsiDirectory getOrCreateSubDirectory(PsiDirectory parentDirectory, String subDirectoryName) {
        PsiDirectory directory = null;
        for (String subDir : subDirectoryName.split("\\.")) {
            directory = parentDirectory.findSubdirectory(subDir);
            if (null == directory) {
                PsiDirectory pParentDirectory = parentDirectory;
                directory = WriteCommandAction.runWriteCommandAction(project, (Computable<PsiDirectory>) () -> pParentDirectory.createSubdirectory(subDir));
            }
            parentDirectory = directory;
        }

        return directory;
    }

    /**
     * 为字段增加Setter与Getter方法
     */
    public void addGetterAndSetterMethods(PsiClass aClass) {
        PsiElementFactory elementFactory = PsiElementFactory.SERVICE.getInstance(project);
        for (PsiField field: aClass.getFields()) {
            String name = field.getName();
            PsiType type = field.getType();

            PsiMethod builderSetter = elementFactory.createMethodFromText(createBuilderSetter(aClass.getName(), name, type.getCanonicalText()), field);
            PsiMethod normalSetter = elementFactory.createMethodFromText(createSetter(name, type.getCanonicalText()), field);
            PsiMethod getter = elementFactory.createMethodFromText(createGetter(name, type.getCanonicalText()), field);

            if (0 == aClass.findMethodsByName(builderSetter.getName()).length) {
                aClass.add(builderSetter);
            }

            if (0 == aClass.findMethodsByName(normalSetter.getName()).length) {
                aClass.add(normalSetter);
            }

            if (0 == aClass.findMethodsByName(getter.getName()).length) {
                aClass.add(getter);
            }
        }
    }

    /**
     * 创建Builder类型的Setter方法
     */
    private String createBuilderSetter(String className, String name, String type) {
        return "public " +
                className +
                " " +
                name +
                "(" +
                type +
                " " +
                name +
                ") {" +
                "this." +
                name +
                " = " +
                name +
                ";" +
                "return this;}";
    }

    /**
     * 创建Getter方法
     */
    private String createSetter(@NotNull String name, String type) {
        return "public void set" +
                name.substring(0, 1).toUpperCase() + name.substring(1) +
                "(" +
                type +
                " " +
                name +
                ") {" +
                "this." +
                name +
                " = " +
                name +
                ";}";
    }

    /**
     * 创建Getter方法
     */
    private String createGetter(String name, String type) {
        return "public " +
                type +
                " get" +
                name.substring(0, 1).toUpperCase() + name.substring(1) +
                "() {return this." +
                name +
                ";}";
    }

    /**
     * 获取资源目录
     */
    private @Nullable VirtualFile getResourceRoot() {
        List<VirtualFile> files = ModuleRootManager.getInstance(this.module)
                .getSourceRoots(JavaModuleSourceRootTypes.RESOURCES);
        if (files.isEmpty()) {
            return null;
        }

        return files.get(0);
    }

    /**
     * 获取资源文件
     */
    public VirtualFile getResourceFile(String fileName) {
        VirtualFile root = this.getResourceRoot();
        if (null == root) {
            return null;
        }
        return root.findFileByRelativePath(fileName);
    }

    /**
     * 获取resource目录下的子目录
     */
    public PsiDirectory getResourceDir(String dir) {
        // 如果dir包含有文件名，需要移除
        if (dir.contains(".")) {
            dir = dir.substring(0, dir.lastIndexOf("/"));
        }
        VirtualFile root = this.getResourceRoot();
        VirtualFile child = root.findChild(dir);
        if (null == child) {
            try {
                child = root.createChildDirectory(this, dir);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        return PsiDirectoryFactory.getInstance(this.project).createDirectory(child);
    }

    /**
     * 获取或者创建资源文件
     * @param fileName 包含路径的文件名
     * @return 创建的资源文件
     */
    public PsiFile getOrCreateResourceFile(String fileName) {
        PsiDirectory directory = this.getResourceDir(fileName);
        fileName = fileName.substring(fileName.lastIndexOf("/") + 1);
        PsiFile file = directory.findFile(fileName);
        if (null == file) {
            file = directory.createFile(fileName);
        }

        return file;
    }
}
