package com.example.demo.service;

import org.apache.poi.xwpf.usermodel.*;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.util.*;

/**
 * 解析 Word (.docx) 文档中的表格，转为结构化数据。
 * 支持智能表名检测：
 * - 如果表格上方有标题段落，取标题作为表名
 * - 否则用 "table_N" 作为表名
 * - 表格第一行默认作为列头
 */
@Service
public class WordParserService {

    /**
     * 解析一个 .docx 文件，返回所有表格的数据。
     * @return [ { "tableName": "...", "columns": [...], "rows": [...], "rowCount": N }, ... ]
     */
    public List<Map<String, Object>> parse(MultipartFile file) {
        List<Map<String, Object>> results = new ArrayList<>();

        try (InputStream is = file.getInputStream();
             XWPFDocument doc = new XWPFDocument(is)) {

            List<IBodyElement> elements = doc.getBodyElements();
            String pendingTitle = null;
            int tableIdx = 0;

            for (int i = 0; i < elements.size(); i++) {
                IBodyElement el = elements.get(i);

                if (el instanceof XWPFParagraph) {
                    XWPFParagraph para = (XWPFParagraph) el;
                    String text = para.getText().trim();
                    // 将紧邻表格上方的段落视为表名候选
                    if (!text.isEmpty() && i + 1 < elements.size()
                            && elements.get(i + 1) instanceof XWPFTable) {
                        pendingTitle = text.length() > 80 ? text.substring(0, 80) : text;
                    }
                }

                if (el instanceof XWPFTable) {
                    XWPFTable table = (XWPFTable) el;
                    List<Map<String, Object>> tableData = parseTable(table);

                    if (tableData.isEmpty()) continue;

                    // 表名
                    String tableName;
                    if (pendingTitle != null) {
                        tableName = sanitizeTableName(pendingTitle);
                        pendingTitle = null;
                    } else {
                        tableName = "doc_table_" + (++tableIdx);
                    }

                    // 列名 (第一行)
                    Map<String, Object> firstRow = tableData.get(0);
                    List<String> columns = new ArrayList<>(firstRow.keySet());

                    // 数据行
                    List<Map<String, Object>> rows = new ArrayList<>();
                    // 第一行是表头，从第二行开始
                    if (tableData.size() > 1) {
                        // 如果第一行看起来像表头（全部是字符串），则作为列名
                        boolean firstIsHeader = firstRow.values().stream()
                                .allMatch(v -> v instanceof String);
                        if (firstIsHeader) {
                            // 用第一行的值作为列名，从第二行开始作为数据
                            List<String> headerValues = new ArrayList<>();
                            for (String col : columns) {
                                headerValues.add(String.valueOf(firstRow.getOrDefault(col, col)));
                            }
                            // 重新构建数据行，使用新的列名
                            for (int r = 1; r < tableData.size(); r++) {
                                Map<String, Object> oldRow = tableData.get(r);
                                Map<String, Object> newRow = new LinkedHashMap<>();
                                for (int c = 0; c < headerValues.size(); c++) {
                                    String key = columns.get(c);
                                    newRow.put(headerValues.get(c), oldRow.get(key));
                                }
                                rows.add(newRow);
                            }
                        } else {
                            rows = tableData;
                        }
                    }

                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("tableName", tableName);
                    result.put("columns", columns);
                    result.put("rows", rows);
                    result.put("rowCount", rows.size());
                    result.put("sourceFile", file.getOriginalFilename());
                    results.add(result);
                }
            }

        } catch (Exception e) {
            throw new RuntimeException("Word 文档解析失败: " + e.getMessage(), e);
        }

        return results;
    }

    /**
     * 解析单个表格，返回每行的 Map 列表
     */
    private List<Map<String, Object>> parseTable(XWPFTable table) {
        List<Map<String, Object>> rows = new ArrayList<>();
        List<XWPFTableRow> tableRows = table.getRows();

        if (tableRows.isEmpty()) return rows;

        // 收集列数 (取最大列数)
        int maxCols = 0;
        for (XWPFTableRow row : tableRows) {
            int size = row.getTableCells().size();
            if (size > maxCols) maxCols = size;
        }

        for (int r = 0; r < tableRows.size(); r++) {
            XWPFTableRow row = tableRows.get(r);
            Map<String, Object> map = new LinkedHashMap<>();

            // 跳过全空行
            boolean allEmpty = true;
            for (int c = 0; c < maxCols; c++) {
                String cellText = getCellText(row, c);
                if (!cellText.isEmpty()) allEmpty = false;
                map.put("col_" + c, guessType(cellText));
            }
            if (allEmpty) continue;

            rows.add(map);
        }

        return rows;
    }

    /** 获取单元格文本 (处理合并单元格) */
    private String getCellText(XWPFTableRow row, int colIdx) {
        List<XWPFTableCell> cells = row.getTableCells();
        if (colIdx >= cells.size()) return "";
        return cells.get(colIdx).getText().trim();
    }

    /** 智能类型推断: 数字 → Number, 其他 → String */
    private Object guessType(String text) {
        if (text == null || text.isEmpty()) return text;
        // 尝试解析为数字
        try {
            if (text.contains(".")) return Double.parseDouble(text);
            return Long.parseLong(text);
        } catch (NumberFormatException e) {
            return text;
        }
    }

    /** 清理表名 */
    private String sanitizeTableName(String raw) {
        return raw.replaceAll("[^\\u4e00-\\u9fa5a-zA-Z0-9_]", "_")
                  .replaceAll("_+", "_")
                  .replaceAll("^_|_$", "");
    }
}
