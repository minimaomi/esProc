package com.scudata.excel;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.sql.Time;
import java.sql.Timestamp;
import java.util.Date;
import java.util.HashMap;

import org.apache.poi.hssf.record.crypto.Biff8EncryptionKey;
import org.apache.poi.hssf.usermodel.HSSFCell;
import org.apache.poi.hssf.usermodel.HSSFCellStyle;
import org.apache.poi.hssf.usermodel.HSSFDataFormat;
import org.apache.poi.hssf.usermodel.HSSFRow;
import org.apache.poi.hssf.usermodel.HSSFSheet;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;

import com.scudata.common.RQException;
import com.scudata.common.StringUtils;
import com.scudata.dm.Env;
import com.scudata.dm.FileObject;

/**
 * Implementation of Excel export function in xls format
 */
public class XlsExporter implements IExcelTool {
	/**
	 * HSSFWorkbook
	 */
	private HSSFWorkbook wb = null;
	/**
	 * HSSFSheet
	 */
	private HSSFSheet sheet = null;
	/**
	 * FileObject
	 */
	private FileObject fo = null;
	/**
	 * The styles used when exporting the sheet
	 */
	private HashMap<Integer, HSSFCellStyle> styles = new HashMap<Integer, HSSFCellStyle>();

	/**
	 * Has title line
	 */
	private boolean hasTitle;
	/**
	 * Export title line
	 */
	private boolean writeTitle;
	/**
	 * Whether to append export
	 */
	private boolean isAppend;
	/**
	 * After the first row of data is written out, save the style and use it
	 * directly later.
	 */
	private boolean resetDataStyle = true;
	/**
	 * Next line to write
	 */
	private int currRow = 0;
	/**
	 * Maximum number of rows
	 */
	private int maxWriteCount = MAX_XLS_LINECOUNT;
	/**
	 * Styles of the row and cells
	 */
	private RowAndCellStyle dataStyle;
	/**
	 * Column styles
	 */
	private HSSFCellStyle[] colStyles;
	/**
	 * Excel password
	 */
	private String pwd;
	/**
	 * Whether the sheet exists
	 */
	private boolean sheetExists = false;

	/**
	 * Constructor
	 * 
	 * @param fo
	 *            FileObject
	 * @param hasTitle
	 *            Has title line
	 * @param isAppend
	 *            Whether to append export
	 * @param sheetName
	 *            Sheet name
	 * @param pwd
	 *            Excel password
	 */
	public XlsExporter(FileObject fo, boolean hasTitle, boolean isAppend,
			Object sheetName, String pwd) {
		this.fo = fo;
		this.hasTitle = hasTitle;
		writeTitle = hasTitle;
		this.isAppend = isAppend;
		this.pwd = pwd;
		InputStream is = null;
		try {
			Biff8EncryptionKey.setCurrentUserPassword(pwd);
			if (fo.isExists() && isAppend) { // The file exists and is appended.
				is = fo.getInputStream();
				wb = new HSSFWorkbook(is);
				if (StringUtils.isValidString(sheetName)) { // Sheet specified
					sheet = wb.getSheet((String) sheetName);
					if (sheet == null) {
						/*
						 * The sheet does not exist, add the sheet.
						 */
						sheet = wb.createSheet();
						int sheetIndex = wb.getSheetIndex(sheet);
						wb.setSheetName(sheetIndex, (String) sheetName);
					} else {
						sheetExists = true;
						/*
						 * If you export the title, export to the last row with
						 * content. The data is imported to the next row.
						 */
						loadStyles();
					}
				} else {
					/* If no sheet is specified, export to the first sheet. */
					int sheetCount = ExcelVersionCompatibleUtilGetter
							.getInstance().getNumberOfSheets(wb);
					if (sheetCount <= 0) {
						/* The original file does not have a sheet */
						sheet = wb.createSheet();
						int sheetIndex = wb.getSheetIndex(sheet);
						wb.setSheetName(sheetIndex, "Sheet1");
					} else { // Export to the first sheet
						sheetExists = true;
						sheet = wb.getSheetAt(0);
						loadStyles();
					}
				}
			} else {
				/*
				 * The file does not exist or does not add to write, write the
				 * file directly.
				 */
				wb = new HSSFWorkbook();
				sheet = wb.createSheet();
				int sheetIndex = wb.getSheetIndex(sheet);
				wb.setSheetName(sheetIndex, StringUtils
						.isValidString(sheetName) ? (String) sheetName
						: "Sheet1");
			}
			int sheetIndex = wb.getSheetIndex(sheet);
			wb.setActiveSheet(sheetIndex);
			wb.setSelectedTab(sheetIndex);
		} catch (Exception e) {
			throw new RQException(e.getMessage());
		} finally {
			if (is != null) {
				try {
					is.close();
				} catch (IOException e) {
				}
			}
			Biff8EncryptionKey.setCurrentUserPassword(null);
		}
	}

	/**
	 * Get the maximum number of rows
	 */
	public int getMaxLineCount() {
		return maxWriteCount;
	}

	/**
	 * Write a row of data
	 * 
	 * @param items
	 *            Object[]
	 */
	public void writeLine(Object[] items) {
		if (sheet == null)
			return;
		HSSFRow hssfRow = null;
		if (currRow <= sheet.getLastRowNum())
			hssfRow = sheet.getRow(currRow);
		if (hssfRow == null)
			hssfRow = sheet.createRow(currRow);
		RowAndCellStyle rowStyle = null;
		if (isAppend) {
			if (writeTitle) {
				rowStyle = getRowStyle(currRow);
			} else {
				rowStyle = dataStyle;
			}
		}
		// RowStyle will be replaced by CellStyle
		writeRowData(hssfRow, items, rowStyle == null ? null
				: rowStyle.rowStyle, rowStyle == null ? null
				: rowStyle.cellStyles);
		if (writeTitle) {
			writeTitle = false;
		} else {
			if (sheetExists && isAppend && resetDataStyle) {
				resetDataStyle(hssfRow);
			}
		}
		currRow++;
	}

	/**
	 * Reset data styles
	 * 
	 * @param hssfRow
	 *            HSSFRow
	 */
	private void resetDataStyle(HSSFRow hssfRow) {
		if (dataStyle == null) {
			dataStyle = new RowAndCellStyle();
		}
		int lastCol = hssfRow.getLastCellNum();
		if (lastCol > 0) {
			HSSFCellStyle[] cellStyles = new HSSFCellStyle[lastCol];
			HSSFCell cell;
			for (int c = 0; c < lastCol; c++) {
				cell = hssfRow.getCell(c);
				if (cell != null)
					cellStyles[c] = cell.getCellStyle();
			}
			dataStyle.cellStyles = cellStyles;
		}
		resetDataStyle = false;
	}

	/**
	 * Write a row of data
	 * 
	 * @param hssfRow
	 *            HSSFRow
	 * @param items
	 *            Row datas
	 * @param rowStyle
	 *            HSSFCellStyle
	 * @param cellStyles
	 *            Cell styles
	 */
	private void writeRowData(Row hssfRow, Object[] items, CellStyle rowStyle,
			CellStyle[] cellStyles) {
		if (items == null || items.length == 0)
			return;
		CellStyle cellStyle, rowOrColStyle = null;
		for (int currCol = 0, maxCol = items.length; currCol < maxCol; currCol++) {
			Cell cell = hssfRow.getCell(currCol);
			if (cell == null) {
				cell = hssfRow.createCell(currCol);
				cellStyle = null;
				if (cellStyles != null && cellStyles.length > currCol) {
					cellStyle = cellStyles[currCol];
					cell.setCellStyle(cellStyle);
				}
			} else {
				cellStyle = cell.getCellStyle();
			}

			if (cellStyle == null) {
				// Grid has no style, set with row style
				if (rowStyle != null) {
					cell.setCellStyle(rowStyle);
					rowOrColStyle = rowStyle;
				} else if (colStyles != null) {
					// Grid and row are not formatted, set with column style
					if (currCol < colStyles.length) {
						if (colStyles[currCol] != null) {
							cell.setCellStyle(colStyles[currCol]);
							rowOrColStyle = colStyles[currCol];
						}
					}
				}

			}
			try {
				/*
				 * Chinese garbled characters below poi3.2 require encoding.
				 * After the version removed this method, it has to be called by
				 * reflection.
				 */
				Method m = HSSFCell.class.getMethod("setEncoding",
						new Class[] { short.class });
				if (m != null)
					m.invoke(cell, new Object[] { new Short((short) 1) });
			} catch (Exception e) {
			}
			Object value = items[currCol];
			if (value instanceof Date) {
				if (value instanceof Time) {
					cell.setCellValue(ExcelUtils.getTimeDouble((Time) value));
				} else {
					cell.setCellValue((Date) value);
				}
				HSSFDataFormat dFormat = wb.createDataFormat();
				if (cellStyle == null
						&& !ExcelUtils.isCellDateFormatted(cell, dFormat)) {
					// Cell is not styled
					HSSFCellStyle style = null;
					short format = 49;
					if (value instanceof Timestamp) {
						format = dFormat.getFormat(Env.getDateTimeFormat());
					} else if (value instanceof Time) {
						format = dFormat.getFormat(Env.getTimeFormat());
					} else {
						format = dFormat.getFormat(Env.getDateFormat());
					}
					style = styles.get(new Integer(currCol));
					if (style == null) {
						style = wb.createCellStyle();
						if (rowOrColStyle != null)
							style.cloneStyleFrom(rowOrColStyle);
						style.setDataFormat(format);
						styles.put(new Integer(currCol), style);
					}
					cell.setCellStyle(style);
				}
			} else if (value instanceof String) {
				String sValue = (String) value;
				if (ExcelUtils.isNumeric(sValue)) {
					cell.setCellType(CellType.STRING);
				}
				cell.setCellValue(sValue);
			} else if (value instanceof Boolean) {
				cell.setCellValue(((Boolean) value).booleanValue());
			} else if (value == null) {
			} else {
				String s = value.toString();
				try {
					double d = Double.parseDouble(s);
					cell.setCellValue(d);
				} catch (Throwable e1) {
					cell.setCellValue(s);
				}
			}
		}
		if (rowStyle != null) {
			hssfRow.setRowStyle(rowStyle);
		}
	}

	/**
	 * If the cell has style, return it, otherwise return the style of the row.
	 * 
	 * @param r
	 *            Row number
	 * @return
	 */
	private RowAndCellStyle getRowStyle(int r) {
		HSSFRow hr = sheet.getRow(r);
		if (hr == null)
			return null;
		RowAndCellStyle style = new RowAndCellStyle();
		style.rowStyle = hr.getRowStyle();
		short lastCol = hr.getLastCellNum();
		if (lastCol > 0) {
			HSSFCellStyle[] cellStyles = new HSSFCellStyle[lastCol];
			for (int c = 0; c < lastCol; c++) {
				HSSFCell cell = hr.getCell(c);
				if (cell != null)
					cellStyles[c] = cell.getCellStyle();
			}
			style.cellStyles = cellStyles;
		}
		style.rowHeight = hr.getHeightInPoints();
		return style;
	}

	/**
	 * Set the name of the sheet to be operated
	 * 
	 * @param name
	 */
	public void setSheet(String name) {
	}

	/**
	 * Set the sequence number of the sheet to be operated.
	 * 
	 * @param name
	 */
	public void setSheet(int index) {
	}

	/**
	 * Total number of rows
	 * 
	 * @return
	 */
	public int totalCount() {
		return 0;
	}

	/**
	 * Set start line
	 * 
	 * @param start
	 */
	public void setStartRow(int start) {
	}

	/**
	 * Set the number of rows to be fetched
	 * 
	 * @param fetchCount
	 */
	public void setFetchCount(int fetchCount) {
	}

	/**
	 * Complete the read and write operations, if it is to write a file, then
	 * output the file at this time.
	 */
	public void output() {
		if (wb != null && fo != null) {
			Biff8EncryptionKey.setCurrentUserPassword(pwd);
			OutputStream out = null;
			try {
				out = fo.getBufferedOutputStream(false);
				wb.write(out);
			} catch (Exception e) {
				throw new RQException(e.getMessage());
			} finally {
				Biff8EncryptionKey.setCurrentUserPassword(null);
				try {
					wb.close();
				} catch (IOException e) {
					e.printStackTrace();
				}
				if (out != null)
					try {
						out.close();
					} catch (IOException e) {
						e.printStackTrace();
					}
			}
		}
	}

	/**
	 * Close
	 * 
	 * @throws IOException
	 */
	public void close() throws IOException {
		output();
	}

	/**
	 * Read a row of data
	 */
	public Object[] readLine() {
		return null;
	}

	/**
	 * Load styles from sheet
	 */
	private void loadStyles() {
		if (sheet == null)
			return;
		try {
			int lastRow = sheet.getLastRowNum();
			if (lastRow < 0) { // û����
				return;
			}
			// �ҵ������ݵ����һ�С����@t������Ϊ������
			// ���û��@t�����滹�п��У�ʹ�ÿ�����ʽ������ʹ�ô�����ʽ
			int lastContentRow = -1;
			HSSFRow hr;
			int colCount = 0;
			for (int r = lastRow; r >= 0; r--) {
				hr = sheet.getRow(r);
				if (hr == null)
					continue;
				int lastCol = hr.getLastCellNum();
				colCount = Math.max(lastCol, colCount);
				if (!ExcelUtils.isEmptyRow(hr, lastCol)) {
					lastContentRow = r;
					break;
				}
			}
			// ȷ�������к�������
			if (hasTitle) { // �б���
				if (lastContentRow == -1) { // û���ҵ���������
					lastContentRow = 0;
				}
				currRow = lastContentRow; // ���Ǳ�����
				dataStyle = getRowStyle(lastContentRow + 1); // ����������һ��
			} else {
				if (lastContentRow == -1) { // û���ҵ���������
					currRow = 0;
				} else {
					currRow = lastContentRow + 1; // ���һ���������еĵ���һ�п�ʼд
				}
				if (lastContentRow < lastRow) { // ����������к��滹���У�����һ�����и�ʽ��Ϊ�����и�ʽ
					dataStyle = getRowStyle(lastContentRow + 1);
				} else {
					dataStyle = getRowStyle(lastContentRow); // ��Ϊ���һ���������е���������
				}
			}

			maxWriteCount -= currRow + 1; // ��currRow��ʼ����д���������

			colStyles = new HSSFCellStyle[colCount];
			for (int c = 0; c < colCount; c++) {
				colStyles[c] = sheet.getColumnStyle(c);
			}

		} catch (Exception e) { // �����������ˣ���֤�����������Ѵ�����Ϣ�����
			e.printStackTrace();
		}
	}
}