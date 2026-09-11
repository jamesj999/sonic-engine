<?xml version="1.0" encoding="UTF-8"?>
<xsl:stylesheet version="1.0" xmlns:xsl="http://www.w3.org/1999/XSL/Transform">
  <xsl:output method="text" encoding="UTF-8"/>
  <xsl:template match="/">
    <xsl:for-each select="//testcase">
      <xsl:value-of select="@classname"/><xsl:text>&#9;</xsl:text>
      <xsl:value-of select="@name"/><xsl:text>&#9;</xsl:text>
      <xsl:choose>
        <xsl:when test="failure"><xsl:text>FAILURE</xsl:text><xsl:text>&#9;</xsl:text><xsl:value-of select="failure/@type"/><xsl:text>&#9;</xsl:text><xsl:value-of select="translate(failure/@message, '&#10;&#13;&#9;', '   ')"/></xsl:when>
        <xsl:when test="error"><xsl:text>ERROR</xsl:text><xsl:text>&#9;</xsl:text><xsl:value-of select="error/@type"/><xsl:text>&#9;</xsl:text><xsl:value-of select="translate(error/@message, '&#10;&#13;&#9;', '   ')"/></xsl:when>
        <xsl:when test="skipped"><xsl:text>SKIPPED</xsl:text><xsl:text>&#9;</xsl:text><xsl:value-of select="skipped/@type"/><xsl:text>&#9;</xsl:text><xsl:value-of select="translate(skipped/@message, '&#10;&#13;&#9;', '   ')"/></xsl:when>
        <xsl:otherwise><xsl:text>PASS</xsl:text><xsl:text>&#9;&#9;</xsl:text></xsl:otherwise>
      </xsl:choose>
      <xsl:text>&#10;</xsl:text>
    </xsl:for-each>
  </xsl:template>
</xsl:stylesheet>
