package io.code.utils;

import io.code.entity.ColumnEntity;
import io.code.entity.EnumEntity;
import io.code.entity.TableEntity;
import jdk.nashorn.internal.runtime.regexp.joni.Config;
import org.apache.commons.configuration.Configuration;
import org.apache.commons.configuration.ConfigurationException;
import org.apache.commons.configuration.PropertiesConfiguration;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.WordUtils;
import org.apache.velocity.Template;
import org.apache.velocity.VelocityContext;
import org.apache.velocity.app.Velocity;

import java.io.File;
import java.io.IOException;
import java.io.StringWriter;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 代码生成器   工具类
 */
public class GenUtils {

    public final static String enumTemplate = "src/main/resources/teamplate/Enum.java.vm";
    public final static String enumFlag = "enum:";
    public final static String enumCommentOneFlag = ",";
    public final static String enumCommentTwoFlag = "_";

    public static List<String> getTemplates() {
        List<String> templates = new ArrayList<String>();

        templates.add("src/main/resources/teamplate/Entity.java.vm");
        templates.add("src/main/resources/teamplate/EntityChild.java.vm");
        templates.add("src/main/resources/teamplate/Dao.java.vm");
        templates.add("src/main/resources/teamplate/ServiceImpl.java.vm");
        templates.add("src/main/resources/teamplate/Controller.java.vm");

        return templates;
    }

    /**
     * 生成代码
     */
    public static void generatorCode(Map<String, String> table,
                                     List<Map<String, String>> columns, ZipOutputStream zip) {
        //配置信息
        Configuration config = getConfig();
        boolean hasBigDecimal = false;
        //表信息
        TableEntity tableEntity = new TableEntity();
        tableEntity.setTableName(table.get("tableName"));
        tableEntity.setComments(table.get("tableComment"));
        //表名转换成Java类名
        String className = tableToJava(tableEntity.getTableName(), config.getString("tablePrefix"));
        tableEntity.setClassName(className);
        tableEntity.setClassname(StringUtils.uncapitalize(className));

        //列信息
        List<ColumnEntity> columsList = new ArrayList<>();
        Boolean generEnum = false;
        String enumName = "";
        for (Map<String, String> column : columns) {
            ColumnEntity columnEntity = new ColumnEntity();
            columnEntity.setColumnName(column.get("columnName"));
            columnEntity.setDataType(column.get("dataType"));
            columnEntity.setComments(column.get("columnComment"));
            columnEntity.setExtra(column.get("extra"));
            //列名转换成Java属性名
            String attrName = columnToJava(columnEntity.getColumnName());
            columnEntity.setAttrName(attrName);
            columnEntity.setAttrname(StringUtils.uncapitalize(attrName));
            boolean enumFlagBoolean = column.get("columnComment").contains(enumFlag);
            columnEntity.setEnumFlag(enumFlagBoolean == true ? 1 : 0);
            if (enumFlagBoolean) {
                columnEntity.setEnumName(attrName + "Enum");
            }
            //生成树形化
            if("gradation_code".equals(columnEntity.getColumnName())){
                tableEntity.setIstree(true);
            }
            //生成枚举
            generateEnum(columnEntity, config, zip, tableEntity);
//            列的数据类型，转换成Java类型
            String attrType = config.getString(columnEntity.getDataType(), "unknowType");
            columnEntity.setAttrType(attrType);
            if (!hasBigDecimal && attrType.equals("BigDecimal")) {
                hasBigDecimal = true;
            }
            //是否主键
            if ("PRI".equalsIgnoreCase(column.get("columnKey")) && tableEntity.getPk() == null) {
                tableEntity.setPk(columnEntity);
            }
            columsList.add(columnEntity);
        }
        tableEntity.setColumns(columsList);

        //没主键，则第一个字段为主键
        if (tableEntity.getPk() == null) {
            tableEntity.setPk(tableEntity.getColumns().get(0));
        }

        //设置velocity资源加载器
        Properties prop = new Properties();
        prop.put("file.resource.loader.class", "org.apache.velocity.runtime.resource.loader.ClasspathResourceLoader");
        Velocity.init(prop);
        String mainPath = config.getString("mainPath");
        mainPath = StringUtils.isBlank(mainPath) ? "io.renren" : mainPath;
        //封装模板数据
        Map<String, Object> map = new HashMap<>();
        map.put("tableName", tableEntity.getTableName());
        map.put("comments", tableEntity.getComments());
        map.put("pk", tableEntity.getPk());
        map.put("className", tableEntity.getClassName());
        map.put("classname", tableEntity.getClassname());
        map.put("pathName", tableEntity.getClassname().toLowerCase());
        map.put("columns", tableEntity.getColumns());
        map.put("hasBigDecimal", hasBigDecimal);
        map.put("mainPath", mainPath);
        map.put("package", config.getString("package"));
        map.put("moduleName", config.getString("moduleName"));
        map.put("author", config.getString("author"));
        map.put("email", config.getString("email"));
        map.put("istree",tableEntity.getIstree());
        map.put("datetime", DateUtils.format(new Date(), DateUtils.DATE_TIME_PATTERN));
        VelocityContext context = new VelocityContext(map);
        //获取模板列表
        List<String> templates = getTemplates();
        for (String template : templates) {
            //渲染模板
            StringWriter sw = new StringWriter();
            Template tpl = Velocity.getTemplate(template, "UTF-8");
            tpl.merge(context, sw);

            try {
                //添加到zip
                zip.putNextEntry(new ZipEntry(getFileName(template, tableEntity.getClassName(), config.getString("package"), config.getString("moduleName"))));
                IOUtils.write(sw.toString(), zip, "UTF-8");
                IOUtils.closeQuietly(sw);
                zip.closeEntry();
            } catch (IOException e) {
                throw new RRException("渲染模板失败，表名：" + tableEntity.getTableName(), e);
            }
        }
    }

    /**
     * 生成枚举    注释; + E: + 英文名_中文名_value,英文名_中文名_value
     * eg: 测试;E:one_是_1,two_否_2
     *
     * @param columnEntity
     * @param config
     * @param zip
     * @param tableEntity
     */
    public static void generateEnum(ColumnEntity columnEntity, Configuration config, ZipOutputStream zip, TableEntity tableEntity) {
        ArrayList<EnumEntity> enumEntities = new ArrayList<>();
        //按照固定的切分规则切分 枚举的具体内容  第一次切分
        String[] flag = columnEntity.getComments().split(enumFlag);
        if (flag.length == 2) {
            //按照, 切分枚举字段   第二次切分
            String[] enums = flag[1].split(enumCommentOneFlag);
            if (enums.length > 1) {
                HashMap<String, Object> map = new HashMap<String, Object>();
                //获取枚举的各个属性
                for (int i = 0; i < enums.length; i++) {
                    String anEnum = enums[i];
                    //按照_切分枚举的各个属性   第三次切分
                    String[] split = anEnum.split(enumCommentTwoFlag);
                    if (split.length == 3) {
                        EnumEntity en = new EnumEntity();
                        en.setEnglishName(split[0].toUpperCase());
                        en.setChineseName(split[1].toUpperCase());
                        en.setValue(split[2]);
                        enumEntities.add(en);
                    }
                }
                if (enumEntities.size() > 0) {
                    map.put("enumName", columnEntity.getAttrName());
                    map.put("enums", enumEntities);
                    map.put("package", config.getString("package"));
                    map.put("moduleName", config.getString("moduleName"));
                    VelocityContext context = new VelocityContext(map);
                    //渲染模板
                    StringWriter sw = new StringWriter();
                    Template tpl = Velocity.getTemplate(enumTemplate, "UTF-8");
                    tpl.merge(context, sw);
                    try {
                        //添加到zip
                        zip.putNextEntry(new ZipEntry(getFileName(enumTemplate, columnEntity.getAttrName(), config.getString("package"), config.getString("moduleName"))));
                        IOUtils.write(sw.toString(), zip, "UTF-8");
                        IOUtils.closeQuietly(sw);
                        zip.closeEntry();
                    } catch (IOException e) {
                        throw new RRException("枚举渲染模板失败，表名：" + tableEntity.getTableName(), e);
                    }
                }

            }

        }
    }

    /**
     * 列名转换成Java属性名
     */
    public static String columnToJava(String columnName) {
        return WordUtils.capitalizeFully(columnName, new char[]{'_'}).replace("_", "");
    }

    /**
     * 表名转换成Java类名
     */
    public static String tableToJava(String tableName, String tablePrefix) {
        if (StringUtils.isNotBlank(tablePrefix)) {
            tableName = tableName.replaceFirst(tablePrefix, "");
        }
        return columnToJava(tableName);
    }

    /**
     * 获取配置信息
     */
    public static Configuration getConfig() {
        try {
            return new PropertiesConfiguration("generator.properties");
        } catch (ConfigurationException e) {
            throw new RRException("获取配置文件失败，", e);
        }
    }

    /**
     * 获取文件名
     */
    public static String getFileName(String template, String className, String packageName, String moduleName) {
        String packagePath = "main" + File.separator + "java" + File.separator;
        if (StringUtils.isNotBlank(packageName)) {
            packagePath += packageName.replace(".", File.separator) + File.separator + moduleName + File.separator;
        }

        if (template.contains("Entity.java.vm")) {
            return packagePath + "entity" + File.separator + "generated" + File.separator + "m" + className + ".java";
        }
        if (template.contains("EntityChild.java.vm")) {
            return packagePath + "entity" + File.separator + className + ".java";
        }

        if (template.contains("Dao.java.vm")) {
            return packagePath + "dao" + File.separator + className + "Dao.java";
        }

        if (template.contains("Enum.java.vm")) {
            return packagePath + "enums" + File.separator + className + "Enum.java";
        }

        if (template.contains("ServiceImpl.java.vm")) {
            return packagePath + "service" + File.separator + className + "ServiceImpl.java";
        }

        if (template.contains("Controller.java.vm")) {
            return packagePath + "controller" + File.separator + className + "Controller.java";
        }


        return null;
    }
//    /**
//     * 获取文件名
//     */
//    public static String getFileName(String template, String className, String packageName, String moduleName) {
//        String packagePath = "main" + File.separator + "java" + File.separator;
//        if (StringUtils.isNotBlank(packageName)) {
//            packagePath += packageName.replace(".", File.separator) + File.separator + moduleName + File.separator;
//        }
//
//        if (template.contains("mEntity.java.vm" )) {
//            return packagePath + "entity" + File.separator + className + "Entity.java";
//        }
//
//        if (template.contains("Dao.java.vm" )) {
//            return packagePath + "dao" + File.separator + className + "Dao.java";
//        }
//
//        if (template.contains("Service.java.vm" )) {
//            return packagePath + "service" + File.separator + className + "Service.java";
//        }
//
//        if (template.contains("ServiceImpl.java.vm" )) {
//            return packagePath + "service" + File.separator + "impl" + File.separator + className + "ServiceImpl.java";
//        }
//
//        if (template.contains("Controller.java.vm" )) {
//            return packagePath + "controller" + File.separator + className + "Controller.java";
//        }
//
//        if (template.contains("Dao.xml.vm" )) {
//            return "main" + File.separator + "resources" + File.separator + "mapper" + File.separator + moduleName + File.separator + className + "Dao.xml";
//        }
//
//        if (template.contains("menu.sql.vm" )) {
//            return className.toLowerCase() + "_menu.sql";
//        }
//
//        if (template.contains("index.vue.vm" )) {
//            return "main" + File.separator + "resources" + File.separator + "src" + File.separator + "views" + File.separator + "modules" +
//                    File.separator + moduleName + File.separator + className.toLowerCase() + ".vue";
//        }
//
//        if (template.contains("add-or-update.vue.vm" )) {
//            return "main" + File.separator + "resources" + File.separator + "src" + File.separator + "views" + File.separator + "modules" +
//                    File.separator + moduleName + File.separator + className.toLowerCase() + "-add-or-update.vue";
//        }
//
//        return null;
//    }
}
