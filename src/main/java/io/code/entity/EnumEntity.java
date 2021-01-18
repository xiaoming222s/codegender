package io.code.entity;

/**
 * 枚举的属性
 */
public class EnumEntity {
	//中文名
    private String  chineseName;
    //英文名
    private String  englishName;
    //值
    private String  value;

    public String getChineseName() {
        return chineseName;
    }

    public void setChineseName(String chineseName) {
        this.chineseName = chineseName;
    }

    public String getEnglishName() {
        return englishName;
    }

    public void setEnglishName(String englishName) {
        this.englishName = englishName;
    }

    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        this.value = value;
    }
}
