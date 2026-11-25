package com.bitsun.tools.pgschemadiff;

/**
 * 对应 information_schema.columns 里单个字段的元信息，用于还原建表用的类型声明。
 */
public class ColumnInfo {
    private final String dataType;
    private final String udtName;
    private final Integer charLen;
    private final Integer numPrecision;
    private final Integer numScale;
    private final String isNullable;
    private String comment;
    private String identityClause;
    private String defaultExpression;
    private String typeDeclaration;

    public ColumnInfo(String dataType, String udtName, Integer charLen,
                       Integer numPrecision, Integer numScale, String isNullable) {
        this.dataType = dataType;
        this.udtName = udtName;
        this.charLen = charLen;
        this.numPrecision = numPrecision;
        this.numScale = numScale;
        this.isNullable = isNullable;
    }

    public String getIdentityClause() {
        return identityClause;
    }

    public void setIdentityClause(String identityClause) {
        this.identityClause = identityClause;
    }

    public String getTypeDeclaration() {
        return typeDeclaration;
    }

    public void setTypeDeclaration(String typeDeclaration) {
        this.typeDeclaration = typeDeclaration;
    }

    public String getDefaultExpression() {
        return defaultExpression;
    }

    public void setDefaultExpression(String defaultExpression) {
        this.defaultExpression = defaultExpression;
    }

    public String getComment() {
        return comment;
    }

    public void setComment(String comment) {
        this.comment = comment;
    }

    public String getDataType() {
        return dataType;
    }

    public String getUdtName() {
        return udtName;
    }

    public Integer getCharLen() {
        return charLen;
    }

    public Integer getNumPrecision() {
        return numPrecision;
    }

    public Integer getNumScale() {
        return numScale;
    }

    public String getIsNullable() {
        return isNullable;
    }
}
