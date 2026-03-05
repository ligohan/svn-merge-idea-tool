package com.svnmerge.helper;

import com.intellij.icons.AllIcons;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.vcs.changes.ChangeListManager;
import com.intellij.openapi.vcs.changes.VcsDirtyScopeManager;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextArea;
import com.intellij.ui.components.JBTextField;
import com.intellij.ui.table.JBTable;

import javax.swing.*;
import javax.swing.text.DefaultCaret;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * SVN Merge Helper 主面板
 */
public class SvnMergeToolWindowPanel extends JPanel {

    private static final String BRANCH_URL_KEY = "svnmerge.branchUrl";
    private static final int SECTION_LABEL_LEFT_PADDING = 3;
    private static final int REVISION_INPUT_HEIGHT = 100;
    private static final int REVISION_INPUT_HAN_CHARS = 11;
    private static final int REVISION_INPUT_EXTRA_WIDTH = 28;
    private static final int PRIMARY_BUTTON_WIDTH = 78;
    private static final int PRIMARY_BUTTON_HEIGHT = 30;
    private static final int SMALL_ACTION_BUTTON_HEIGHT = 18;
    private static final int BUTTON_ROW_HEIGHT = 42;
    private static final int BUTTON_ROW_HGAP = 6;

    private final Project project;
    private final SvnCommandExecutor executor = new SvnCommandExecutor();

    private final JBTextField branchUrlField;
    private final JBTextField keywordField;
    private final JBTextField authorField;
    private final JBTextField searchLimitField;
    private final JBTextField revisionsField;
    private final JBTable logTable;
    private final DefaultTableModel logTableModel;
    private final JBTextArea outputArea;
    private final JButton loadButton;
    private final JBTextField loadLimitField;
    private final JButton queryButton;
    private final JCheckBox queryAppendCheckBox;
    private final JButton searchButton;
    private final JButton mergeButton;
    private final JButton commitButton;
    private final JCheckBox unmergedFilterCheckBox;

    /** 查询结果缓存，与表格行一一对应 */
    private final List<SvnCommandExecutor.LogEntry> logEntries = new ArrayList<>();
    /** 查询是否成功完成，修改版本号后重置 */
    private boolean queryDone = false;
    /** 是否正在执行查询或合并操作 */
    private boolean operating = false;

    /** 冲突等待面板，显示在插件内容区域上方 */
    private JPanel conflictWaitingPanel;
    private java.util.concurrent.atomic.AtomicBoolean conflictUserChoice;
    private java.util.concurrent.CountDownLatch conflictLatch;

    public SvnMergeToolWindowPanel(Project project) {
        this.project = project;
        setLayout(new BorderLayout());

        JPanel contentPanel = new JPanel();
        contentPanel.setLayout(new BoxLayout(contentPanel, BoxLayout.Y_AXIS));
        contentPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        // 源分支地址标题行（标签 + 自动获取按钮）
        JPanel branchLabelPanel = new JPanel();
        branchLabelPanel.setLayout(new BoxLayout(branchLabelPanel, BoxLayout.X_AXIS));
        branchLabelPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        branchLabelPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 20));
        branchLabelPanel.add(createLabel("源分支地址:"));
        branchLabelPanel.add(Box.createHorizontalStrut(6));
        JButton autoFetchButton = new JButton("自动获取");
        autoFetchButton.setMargin(new Insets(0, 4, 0, 4));
        autoFetchButton.setFont(autoFetchButton.getFont().deriveFont(Font.PLAIN, 12f));
        autoFetchButton.setForeground(new Color(0x4A90D9));
        autoFetchButton.setBorder(BorderFactory.createLineBorder(new Color(0x4A90D9)));
        autoFetchButton.setPreferredSize(new Dimension(66, SMALL_ACTION_BUTTON_HEIGHT));
        autoFetchButton.setMaximumSize(new Dimension(66, SMALL_ACTION_BUTTON_HEIGHT));
        branchLabelPanel.add(autoFetchButton);
        contentPanel.add(branchLabelPanel);
        contentPanel.add(Box.createVerticalStrut(4));
        branchUrlField = new JBTextField();
        branchUrlField.setText(PropertiesComponent.getInstance(project).getValue(BRANCH_URL_KEY, ""));
        autoFetchButton.addActionListener(e -> {
            String workingDir = project.getBasePath();
            if (workingDir == null) {
                showFloatingTip(autoFetchButton, "无法获取项目根目录");
                return;
            }
            ApplicationManager.getApplication().executeOnPooledThread(() -> {
                String url = executor.queryTrunkUrl(workingDir);
                SwingUtilities.invokeLater(() -> {
                    if (url != null && !url.isEmpty()) {
                        branchUrlField.setText(url);
                        PropertiesComponent.getInstance(project).setValue(BRANCH_URL_KEY, url);
                    } else {
                        showFloatingTip(autoFetchButton, "获取失败，请检查 SVN Trunk Location 配置");
                    }
                });
            });
        });
        branchUrlField.setHorizontalAlignment(JTextField.LEFT);
        branchUrlField.setAlignmentX(Component.LEFT_ALIGNMENT);
        Dimension fieldPrefSize = branchUrlField.getPreferredSize();
        branchUrlField.setPreferredSize(new Dimension(0, fieldPrefSize.height));
        branchUrlField.setMaximumSize(new Dimension(Integer.MAX_VALUE, fieldPrefSize.height));
        contentPanel.add(branchUrlField);
        contentPanel.add(Box.createVerticalStrut(4));

        // 加载按钮行（加载 + 数字输入框 + 条）
        JPanel loadPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, BUTTON_ROW_HGAP, 0));
        loadPanel.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 0));
        loadPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, BUTTON_ROW_HEIGHT));
        loadPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        loadButton = new JButton("加载");
        styleButton(loadButton, new Color(0x3C3F41));
        loadButton.setPreferredSize(new Dimension(PRIMARY_BUTTON_WIDTH, PRIMARY_BUTTON_HEIGHT));
        loadPanel.add(loadButton);
        loadLimitField = new JBTextField("30");
        loadLimitField.setHorizontalAlignment(JTextField.CENTER);
        // 纯数字过滤
        ((javax.swing.text.AbstractDocument) loadLimitField.getDocument()).setDocumentFilter(
                new javax.swing.text.DocumentFilter() {
                    private String filter(String text) {
                        if (text == null) return null;
                        StringBuilder sb = new StringBuilder();
                        for (char c : text.toCharArray()) {
                            if (Character.isDigit(c)) sb.append(c);
                        }
                        return sb.toString();
                    }

                    @Override
                    public void insertString(FilterBypass fb, int offset, String string,
                            javax.swing.text.AttributeSet attr) throws javax.swing.text.BadLocationException {
                        String filtered = filter(string);
                        String newText = fb.getDocument().getText(0, fb.getDocument().getLength());
                        newText = newText.substring(0, offset) + filtered + newText.substring(offset);
                        int val = newText.isEmpty() ? 0 : Integer.parseInt(newText);
                        if (val <= 500) {
                            super.insertString(fb, offset, filtered, attr);
                        }
                    }

                    @Override
                    public void replace(FilterBypass fb, int offset, int length, String text,
                            javax.swing.text.AttributeSet attrs) throws javax.swing.text.BadLocationException {
                        String filtered = filter(text);
                        String current = fb.getDocument().getText(0, fb.getDocument().getLength());
                        String newText = current.substring(0, offset) + filtered + current.substring(offset + length);
                        int val = newText.isEmpty() ? 0 : Integer.parseInt(newText);
                        if (val <= 500) {
                            super.replace(fb, offset, length, filtered, attrs);
                        }
                    }
                });
        Dimension loadLimitSize = new Dimension(50, fieldPrefSize.height);
        loadLimitField.setPreferredSize(loadLimitSize);
        loadLimitField.setMinimumSize(loadLimitSize);
        loadLimitField.setMaximumSize(loadLimitSize);
        loadPanel.add(loadLimitField);
        loadPanel.add(new JLabel("条"));
        contentPanel.add(loadPanel);
        contentPanel.add(Box.createVerticalStrut(8));

        // 关键字搜索标签行（关键字搜索 + 版本范围标签同一行）
        JPanel searchLabelPanel = new JPanel();
        searchLabelPanel.setLayout(new BoxLayout(searchLabelPanel, BoxLayout.X_AXIS));
        searchLabelPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        searchLabelPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 20));
        searchLabelPanel.add(createLabel("关键字:"));
        // 用水平胶水把版本范围标签推到右侧对齐输入框
        searchLabelPanel.add(Box.createHorizontalGlue());
        JLabel authorLabel = new JLabel("作者:");
        authorField = new JBTextField();
        int authorFieldWidth = authorField.getFontMetrics(authorField.getFont())
                .stringWidth("W".repeat(8)) + 8;
        searchLabelPanel.add(Box.createHorizontalStrut(8));
        authorLabel.setPreferredSize(new Dimension(authorFieldWidth, 20));
        authorLabel.setMinimumSize(new Dimension(authorFieldWidth, 20));
        authorLabel.setMaximumSize(new Dimension(authorFieldWidth, 20));
        searchLabelPanel.add(authorLabel);
        JLabel limitLabel = new JLabel("版本范围:");
        searchLimitField = new JBTextField("HEAD:HEAD-1000");
        int limitFieldWidth = getHanTextWidth(searchLimitField, 10) + REVISION_INPUT_EXTRA_WIDTH;
        // 加上与输入行相同的 8px 间距，使标签与输入框左对齐
        searchLabelPanel.add(Box.createHorizontalStrut(8));
        limitLabel.setPreferredSize(new Dimension(limitFieldWidth, 20));
        limitLabel.setMinimumSize(new Dimension(limitFieldWidth, 20));
        limitLabel.setMaximumSize(new Dimension(limitFieldWidth, 20));
        searchLabelPanel.add(limitLabel);
        contentPanel.add(searchLabelPanel);
        contentPanel.add(Box.createVerticalStrut(4));

        // 关键字搜索输入行
        JPanel searchInputPanel = new JPanel();
        searchInputPanel.setLayout(new BoxLayout(searchInputPanel, BoxLayout.X_AXIS));
        searchInputPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        searchInputPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, fieldPrefSize.height));

        keywordField = new JBTextField();
        keywordField.setToolTipText("输入搜索关键字");
        keywordField.addActionListener(e -> doSearch());
        keywordField.setPreferredSize(new Dimension(0, fieldPrefSize.height));
        keywordField.setMaximumSize(new Dimension(Integer.MAX_VALUE, fieldPrefSize.height));
        searchInputPanel.add(keywordField);

        searchInputPanel.add(Box.createHorizontalStrut(8));

        authorField.setToolTipText("输入作者（可选）");
        authorField.addActionListener(e -> doSearch());
        Dimension authorFieldSize = new Dimension(authorFieldWidth, fieldPrefSize.height);
        authorField.setPreferredSize(authorFieldSize);
        authorField.setMinimumSize(authorFieldSize);
        authorField.setMaximumSize(authorFieldSize);
        searchInputPanel.add(authorField);

        searchInputPanel.add(Box.createHorizontalStrut(8));

        Dimension limitFieldSize = new Dimension(limitFieldWidth, fieldPrefSize.height);
        searchLimitField.setPreferredSize(limitFieldSize);
        searchLimitField.setMinimumSize(limitFieldSize);
        searchLimitField.setMaximumSize(limitFieldSize);
        searchInputPanel.add(searchLimitField);

        contentPanel.add(searchInputPanel);
        contentPanel.add(Box.createVerticalStrut(4));

        // 搜索按钮行
        JPanel searchPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, BUTTON_ROW_HGAP, 0));
        searchPanel.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 0));
        searchPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, BUTTON_ROW_HEIGHT));
        searchPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        searchButton = new JButton("搜索");
        styleButton(searchButton, new Color(0x3C3F41));
        searchButton.setPreferredSize(new Dimension(PRIMARY_BUTTON_WIDTH, PRIMARY_BUTTON_HEIGHT));
        searchPanel.add(searchButton);
        contentPanel.add(searchPanel);
        contentPanel.add(Box.createVerticalStrut(8));

        // 版本号（只读输入框，点击弹窗编辑）
        contentPanel.add(createLabel("版本号:"));
        contentPanel.add(Box.createVerticalStrut(4));
        revisionsField = new JBTextField();
        revisionsField.setEditable(false);
        revisionsField.setToolTipText("点击编辑");
        revisionsField.setAlignmentX(Component.LEFT_ALIGNMENT);
        Dimension revFieldSize = revisionsField.getPreferredSize();
        revisionsField.setPreferredSize(new Dimension(0, revFieldSize.height));
        revisionsField.setMaximumSize(new Dimension(Integer.MAX_VALUE, revFieldSize.height));
        revisionsField.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                showRevisionsEditDialog();
            }
        });
        contentPanel.add(revisionsField);
        contentPanel.add(Box.createVerticalStrut(8));

        // 查询按钮行
        JPanel queryPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, BUTTON_ROW_HGAP, 0));
        queryPanel.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 0));
        queryPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, BUTTON_ROW_HEIGHT));
        queryPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        queryButton = new JButton("查询");
        styleButton(queryButton, new Color(0x3C3F41));
        queryButton.setPreferredSize(new Dimension(PRIMARY_BUTTON_WIDTH, PRIMARY_BUTTON_HEIGHT));
        queryPanel.add(queryButton);
        queryAppendCheckBox = new JCheckBox("追加");
        queryPanel.add(queryAppendCheckBox);
        contentPanel.add(queryPanel);
        contentPanel.add(Box.createVerticalStrut(10));

        mergeButton = new JButton("合并");
        styleButton(mergeButton, new Color(0x4A90D9));
        mergeButton.setPreferredSize(new Dimension(PRIMARY_BUTTON_WIDTH, PRIMARY_BUTTON_HEIGHT));

        // 提交信息标题行（标签 + 删除/清空按钮）
        JPanel logLabelPanel = new JPanel();
        logLabelPanel.setLayout(new BoxLayout(logLabelPanel, BoxLayout.X_AXIS));
        logLabelPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel logLabel = createLabel("提交信息（双击查看变动文件）:");
        int logLabelRowHeight = 26;
        logLabelPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, logLabelRowHeight));
        logLabelPanel.add(logLabel);
        logLabelPanel.add(Box.createHorizontalGlue());
        unmergedFilterCheckBox = new JCheckBox("未合并");
        logLabelPanel.add(unmergedFilterCheckBox);
        logLabelPanel.add(Box.createHorizontalStrut(8));
        JButton deleteSelectedButton = new JButton("删除");
        deleteSelectedButton.setMargin(new Insets(0, 4, 0, 4));
        deleteSelectedButton.setFont(deleteSelectedButton.getFont().deriveFont(Font.PLAIN, 12f));
        deleteSelectedButton.setForeground(new Color(0x4A90D9));
        deleteSelectedButton.setBorder(BorderFactory.createLineBorder(new Color(0x4A90D9)));
        int logActionButtonWidth = getHanTextWidth(deleteSelectedButton, 2) + 10;
        deleteSelectedButton.setPreferredSize(new Dimension(logActionButtonWidth, SMALL_ACTION_BUTTON_HEIGHT));
        deleteSelectedButton.setMaximumSize(new Dimension(logActionButtonWidth, SMALL_ACTION_BUTTON_HEIGHT));
        JButton clearButton = new JButton("清空");
        clearButton.setMargin(new Insets(0, 4, 0, 4));
        clearButton.setFont(clearButton.getFont().deriveFont(Font.PLAIN, 12f));
        clearButton.setForeground(new Color(0x4A90D9));
        clearButton.setBorder(BorderFactory.createLineBorder(new Color(0x4A90D9)));
        clearButton.setPreferredSize(new Dimension(logActionButtonWidth, SMALL_ACTION_BUTTON_HEIGHT));
        clearButton.setMaximumSize(new Dimension(logActionButtonWidth, SMALL_ACTION_BUTTON_HEIGHT));
        logLabelPanel.add(deleteSelectedButton);
        logLabelPanel.add(Box.createHorizontalStrut(8));
        logLabelPanel.add(clearButton);
        contentPanel.add(logLabelPanel);
        contentPanel.add(Box.createVerticalStrut(4));
        logTableModel = new DefaultTableModel(
                new String[]{"", "版本号", "作者", "提交信息（经提取)", "提交时间", "状态"}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return column == 0; // 只有勾选列可编辑
            }

            @Override
            public Class<?> getColumnClass(int columnIndex) {
                return columnIndex == 0 ? Boolean.class : String.class;
            }
        };
        logTable = new JBTable(logTableModel) {
            private int prePressRow = -1;
            private boolean wasPressRowSelected = false;

            @Override
            protected void processMouseEvent(MouseEvent e) {
                if (e.getID() == MouseEvent.MOUSE_PRESSED) {
                    prePressRow = rowAtPoint(e.getPoint());
                    wasPressRowSelected = prePressRow >= 0 && isRowSelected(prePressRow);
                }
                super.processMouseEvent(e);
                if (e.getID() == MouseEvent.MOUSE_CLICKED) {
                    if (e.getClickCount() == 2) {
                        onLogTableDoubleClick();
                    } else if (e.getClickCount() == 1 && wasPressRowSelected) {
                        int row = rowAtPoint(e.getPoint());
                        if (row >= 0 && row == prePressRow) {
                            clearSelection();
                        }
                    }
                }
            }
        };
        logTable.getColumnModel().getColumn(0).setPreferredWidth(30);
        logTable.getColumnModel().getColumn(0).setMinWidth(30);
        logTable.getColumnModel().getColumn(0).setMaxWidth(30);
        logTable.getColumnModel().getColumn(1).setPreferredWidth(70);
        logTable.getColumnModel().getColumn(1).setMinWidth(70);
        logTable.getColumnModel().getColumn(1).setMaxWidth(70);
        logTable.getColumnModel().getColumn(2).setPreferredWidth(100);
        logTable.getColumnModel().getColumn(2).setMaxWidth(150);
        logTable.getColumnModel().getColumn(3).setPreferredWidth(300);
        logTable.getColumnModel().getColumn(4).setPreferredWidth(110);
        logTable.getColumnModel().getColumn(4).setMinWidth(110);
        logTable.getColumnModel().getColumn(4).setMaxWidth(110);
        logTable.getColumnModel().getColumn(5).setPreferredWidth(60);
        logTable.getColumnModel().getColumn(5).setMinWidth(60);
        logTable.getColumnModel().getColumn(5).setMaxWidth(60);
        // 固定其余列，仅让“提交信息”列随表格宽度变化。
        logTable.setAutoResizeMode(JTable.AUTO_RESIZE_SUBSEQUENT_COLUMNS);
        logTable.setRowHeight(24);

        // 表头勾选框：全选/全不选
        JCheckBox headerCheckBox = new JCheckBox();
        headerCheckBox.setSelected(false);
        headerCheckBox.setHorizontalAlignment(SwingConstants.CENTER);
        Runnable refreshHeaderCheckBoxState = () -> {
            int total = logTableModel.getRowCount();
            if (total == 0) {
                headerCheckBox.setSelected(false);
                logTable.getTableHeader().repaint();
                return;
            }
            boolean allSelected = true;
            for (int i = 0; i < total; i++) {
                if (!Boolean.TRUE.equals(logTableModel.getValueAt(i, 0))) {
                    allSelected = false;
                    break;
                }
            }
            headerCheckBox.setSelected(allSelected);
            logTable.getTableHeader().repaint();
        };
        headerCheckBox.addActionListener(e -> {
            boolean selected = headerCheckBox.isSelected();
            for (int i = 0; i < logTableModel.getRowCount(); i++) {
                logTableModel.setValueAt(selected, i, 0);
            }
            refreshHeaderCheckBoxState.run();
        });
        // 数据行勾选变化时，更新表头勾选框状态
        logTableModel.addTableModelListener(e -> {
            if (e.getColumn() == 0 || e.getColumn() == javax.swing.event.TableModelEvent.ALL_COLUMNS) {
                refreshHeaderCheckBoxState.run();
            }
        });
        logTable.getColumnModel().getColumn(0).setHeaderRenderer(
                (table, value, isSelected, hasFocus, row, column) -> headerCheckBox);
        // 表头点击事件：点击勾选列表头时切换全选状态
        logTable.getTableHeader().addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                int col = logTable.getTableHeader().columnAtPoint(e.getPoint());
                if (col == 0) {
                    headerCheckBox.doClick();
                }
            }
        });
        // 状态列着色渲染器（第5列）
        logTable.getColumnModel().getColumn(5).setCellRenderer(new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable table, Object value,
                    boolean isSelected, boolean hasFocus, int row, int column) {
                Component c = super.getTableCellRendererComponent(
                        table, value, isSelected, hasFocus, row, column);
                if (!isSelected && value != null) {
                    String status = value.toString();
                    if ("已合并".equals(status)) {
                        c.setForeground(new Color(0x5CB85C)); // 绿色
                    } else if ("未合并".equals(status)) {
                        c.setForeground(new Color(0x4A90D9)); // 蓝色
                    } else {
                        c.setForeground(table.getForeground());
                    }
                }
                return c;
            }
        });
        // 鼠标悬停时在提交信息列显示完整内容的 tooltip
        logTable.addMouseMotionListener(new MouseMotionAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                int row = logTable.rowAtPoint(e.getPoint());
                int col = logTable.columnAtPoint(e.getPoint());
                if (row >= 0 && col == 3) {
                    String revision = String.valueOf(logTable.getValueAt(row, 1));
                    SvnCommandExecutor.LogEntry entry = findEntryByRevision(revision);
                    String tip = buildCommitTooltip(entry);
                    logTable.setToolTipText(tip);
                } else {
                    logTable.setToolTipText(null);
                }
            }
        });
        // Ctrl+C / Cmd+C 复制选中行的提交信息
        int copyModifier = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
        KeyStroke copyKey = KeyStroke.getKeyStroke(KeyEvent.VK_C, copyModifier);
        logTable.getInputMap(JComponent.WHEN_FOCUSED).put(copyKey, "copyCommitMessage");
        logTable.getActionMap().put("copyCommitMessage", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                int[] rows = logTable.getSelectedRows();
                if (rows.length == 0) return;
                StringBuilder sb = new StringBuilder();
                for (int row : rows) {
                    if (sb.length() > 0) sb.append("\n");
                    // 版本号、作者、提交信息、提交时间、状态
                    sb.append(logTable.getValueAt(row, 1)).append("\t")
                      .append(logTable.getValueAt(row, 2)).append("\t")
                      .append(logTable.getValueAt(row, 3)).append("\t")
                      .append(logTable.getValueAt(row, 4)).append("\t")
                      .append(logTable.getValueAt(row, 5));
                }
                Toolkit.getDefaultToolkit().getSystemClipboard()
                        .setContents(new StringSelection(sb.toString()), null);
            }
        });
        JBScrollPane logScroll = new JBScrollPane(logTable);
        // 8 行数据 + 表头
        int logTableHeight = logTable.getRowHeight() * 8 + logTable.getTableHeader().getPreferredSize().height;
        logScroll.setMinimumSize(new Dimension(0, logTableHeight));
        logScroll.setPreferredSize(new Dimension(0, logTableHeight));
        logScroll.setMaximumSize(new Dimension(Integer.MAX_VALUE, logTableHeight));
        contentPanel.add(logScroll);
        contentPanel.add(Box.createVerticalStrut(8));

        // 合并按钮行（合并 + 提交）
        JButton revertButton = new JButton("还原");
        styleButton(revertButton, new Color(0x3C3F41));
        revertButton.setPreferredSize(new Dimension(PRIMARY_BUTTON_WIDTH, PRIMARY_BUTTON_HEIGHT));
        revertButton.addActionListener(e -> doRevert());

        commitButton = new JButton("提交");
        styleButton(commitButton, new Color(0x4A90D9));
        commitButton.setPreferredSize(new Dimension(PRIMARY_BUTTON_WIDTH, PRIMARY_BUTTON_HEIGHT));
        commitButton.addActionListener(e -> doCommit());

        JPanel mergePanel = new JPanel(new FlowLayout(FlowLayout.LEFT, BUTTON_ROW_HGAP, 0));
        mergePanel.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 0));
        mergePanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, BUTTON_ROW_HEIGHT));
        mergePanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        mergePanel.add(mergeButton);
        mergePanel.add(revertButton);
        mergePanel.add(commitButton);
        contentPanel.add(mergePanel);
        contentPanel.add(Box.createVerticalStrut(8));

        // 日志输出
        contentPanel.add(createLabel("日志输出:"));
        contentPanel.add(Box.createVerticalStrut(4));
        outputArea = new JBTextArea(3, 0);
        outputArea.setEditable(false);
        outputArea.setLineWrap(true);
        outputArea.setWrapStyleWord(true);
        outputArea.setMargin(new Insets(6, 8, 6, 8));
        if (outputArea.getCaret() instanceof DefaultCaret) {
            ((DefaultCaret) outputArea.getCaret()).setUpdatePolicy(DefaultCaret.ALWAYS_UPDATE);
        }
        JBScrollPane outputScroll = new JBScrollPane(outputArea);
        outputScroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        // 8 行文本高度 -> 10 行
        int outputLineHeight = outputArea.getFontMetrics(outputArea.getFont()).getHeight();
        int outputHeight = outputLineHeight * 8 + 8;
        outputScroll.setMinimumSize(new Dimension(0, outputHeight));
        outputScroll.setPreferredSize(new Dimension(0, outputHeight));
        outputScroll.setMaximumSize(new Dimension(Integer.MAX_VALUE, outputHeight));
        contentPanel.add(outputScroll);
        contentPanel.add(Box.createVerticalStrut(8));

        add(new JBScrollPane(contentPanel), BorderLayout.CENTER);

        // 事件绑定
        loadButton.addActionListener(e -> doLoad());
        queryButton.addActionListener(e -> doQuery(queryAppendCheckBox.isSelected()));
        searchButton.addActionListener(e -> doSearch());
        mergeButton.addActionListener(e -> doMerge());
        unmergedFilterCheckBox.addActionListener(e -> applyUnmergedFilter());
        deleteSelectedButton.addActionListener(e -> {
            int[] selectedRows = logTable.getSelectedRows();
            if (selectedRows.length == 0) return;
            for (int i = selectedRows.length - 1; i >= 0; i--) {
                int row = selectedRows[i];
                if (row >= 0 && row < logTableModel.getRowCount()) {
                    logTableModel.removeRow(row);
                    logEntries.remove(row);
                }
            }
        });
        clearButton.addActionListener(e -> {
            logTableModel.setRowCount(0);
            logEntries.clear();
            queryDone = false;
            unmergedFilterCheckBox.setSelected(false);
        });

        // 首次打开插件窗口
        SwingUtilities.invokeLater(() -> {
            if (!branchUrlField.getText().trim().isEmpty()) {
                // 源分支地址有值，自动加载
                doLoad();
            } else {
                // 源分支地址为空，自动获取一次
                String workingDir = project.getBasePath();
                if (workingDir != null) {
                    ApplicationManager.getApplication().executeOnPooledThread(() -> {
                        // 仅从 .idea/misc.xml 读取 Trunk Location
                        String url = executor.queryTrunkUrl(workingDir);
                        String finalUrl = url;
                        SwingUtilities.invokeLater(() -> {
                            if (finalUrl != null && !finalUrl.isEmpty()) {
                                branchUrlField.setText(finalUrl);
                                PropertiesComponent.getInstance(project).setValue(BRANCH_URL_KEY, finalUrl);
                                doLoad();
                            }
                        });
                    });
                }
            }
        });
    }

    private int getHanTextWidth(JComponent component, int hanCharCount) {
        int safeHanCharCount = Math.max(1, hanCharCount);
        FontMetrics fontMetrics = component.getFontMetrics(component.getFont());
        return fontMetrics.stringWidth("汉".repeat(safeHanCharCount));
    }

    /** 根据版本号查找 logEntries 中的条目 */
    private SvnCommandExecutor.LogEntry findEntryByRevision(String revision) {
        for (SvnCommandExecutor.LogEntry entry : logEntries) {
            if (entry.revision.equals(revision)) {
                return entry;
            }
        }
        return null;
    }

    /** 表格显示用的提交信息，以 "Merge(d) from/From xxx" 开头时去掉该前缀 */
    private static final java.util.regex.Pattern MERGE_PREFIX_PATTERN =
            java.util.regex.Pattern.compile("^Merged? [Ff]rom\\s+\\S+\\s*");
    /** 行尾包含一个或多个 "[from revision XXX]" 时去掉全部后缀（中间可有或没有空白） */
    private static final java.util.regex.Pattern FROM_REVISION_SUFFIX_PATTERN =
            java.util.regex.Pattern.compile("\\s*(?:\\[from\\s+revision\\s+[0-9,]+]\\s*)+$",
                    java.util.regex.Pattern.CASE_INSENSITIVE);
    /** 建议提交信息格式：提交信息 | 作者 | 版本号，表格仅展示第一段提交信息 */
    private static final java.util.regex.Pattern SUGGESTED_COMMIT_MESSAGE_PATTERN =
            java.util.regex.Pattern.compile("^\\s*([^|\\r\\n]+?)\\s*\\|\\s*[^|\\r\\n]+\\s*\\|\\s*[^|\\r\\n]+\\s*$");
    /** 表格显示用的提交时间格式（简短） */
    private static final DateTimeFormatter COMMIT_TIME_TABLE_FORMATTER =
            DateTimeFormatter.ofPattern("yy/MM/dd HH:mm");
    /** Tooltip 显示用的提交时间格式（完整） */
    private static final DateTimeFormatter COMMIT_TIME_TOOLTIP_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss");

    private static String displayMessage(String message) {
        if (message == null) return null;
        String normalized = message.replace("\r\n", "\n").replace("\r", "\n");
        String[] lines = normalized.split("\n", -1);
        StringBuilder converted = new StringBuilder();
        boolean hasContentLine = false;
        for (int i = 0; i < lines.length; i++) {
            String originalLine = lines[i];
            String convertedLine = convertMessageLine(originalLine);
            if (convertedLine.trim().isEmpty()) {
                continue;
            }
            if (hasContentLine) converted.append('\n');
            converted.append(convertedLine);
            hasContentLine = true;
        }
        return converted.toString();
    }

    private static String convertMessageLine(String line) {
        String display = line;
        java.util.regex.Matcher m = MERGE_PREFIX_PATTERN.matcher(display);
        if (m.find()) {
            display = display.substring(m.end());
        }
        display = FROM_REVISION_SUFFIX_PATTERN.matcher(display).replaceFirst("");
        java.util.regex.Matcher suggested = SUGGESTED_COMMIT_MESSAGE_PATTERN.matcher(display);
        if (suggested.matches()) {
            return suggested.group(1).trim();
        }
        return display;
    }

    private static String buildCommitTooltip(SvnCommandExecutor.LogEntry entry) {
        if (entry == null) return null;
        String message = entry.message == null ? "" : entry.message;
        String revision = entry.revision == null ? "" : entry.revision;
        String displayTime = formatCommitTimeForTooltip(entry.commitTime);
        String author = entry.author == null ? "" : entry.author;
        String body = escapeHtml(message)
                .replace("\r\n", "\n")
                .replace("\r", "\n")
                .replace("\n", "<br/>");
        return "<html>"
                + "提交时间：" + escapeHtml(displayTime) + "<br/>"
                + "版本号：" + escapeHtml(revision) + "<br/>"
                + "作者：" + escapeHtml(author) + "<br/>"
                + "<br/>"
                + "Commit Message：" + "<br/>"
                + body
                + "</html>";
    }

    private static String escapeHtml(String text) {
        if (text == null || text.isEmpty()) return "";
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    private static String formatCommitTime(String commitTime) {
        if (commitTime == null) return "";
        String trimmed = commitTime.trim();
        if (trimmed.isEmpty()) return "";
        try {
            return OffsetDateTime.parse(trimmed)
                    .atZoneSameInstant(ZoneId.systemDefault())
                    .format(COMMIT_TIME_TABLE_FORMATTER);
        } catch (DateTimeParseException ignored) {
            return trimmed;
        }
    }

    /** 格式化提交时间用于 tooltip 显示（完整格式） */
    private static String formatCommitTimeForTooltip(String commitTime) {
        if (commitTime == null) return "";
        String trimmed = commitTime.trim();
        if (trimmed.isEmpty()) return "";
        try {
            return OffsetDateTime.parse(trimmed)
                    .atZoneSameInstant(ZoneId.systemDefault())
                    .format(COMMIT_TIME_TOOLTIP_FORMATTER);
        } catch (DateTimeParseException ignored) {
            return trimmed;
        }
    }

    /** 双击表格行，弹出变动文件列表 */
    private void onLogTableDoubleClick() {
        int row = logTable.getSelectedRow();
        if (row < 0 || row >= logTableModel.getRowCount()) return;
        String revision = String.valueOf(logTableModel.getValueAt(row, 1));
        SvnCommandExecutor.LogEntry entry = findEntryByRevision(revision);
        if (entry == null) return;
        String branchUrl = getBranchUrl();

        // 如果变动文件列表为空，异步查询
        if (entry.changedFiles.isEmpty()) {
            ApplicationManager.getApplication().executeOnPooledThread(() -> {
                SvnCommandExecutor.LogEntry detailed = executor.queryLogVerbose(branchUrl, entry.revision);
                if (detailed != null) {
                    entry.changedFiles.addAll(detailed.changedFiles);
                }
                SwingUtilities.invokeLater(() -> showChangedFilesDialog(entry, branchUrl));
            });
        } else {
            showChangedFilesDialog(entry, branchUrl);
        }
    }

    /** 设置按钮样式：圆角、白色字体、常规粗细 */
    private static void styleButton(JButton btn, Color bgColor) {
        btn.setFont(btn.getFont().deriveFont(Font.PLAIN));
        btn.setForeground(Color.WHITE);
        btn.setContentAreaFilled(false);
        btn.setBorderPainted(false);
        btn.setFocusPainted(false);
        btn.setOpaque(false);
        btn.setMargin(new Insets(6, 20, 6, 20));
        btn.setUI(new javax.swing.plaf.basic.BasicButtonUI() {
            @Override
            public void paint(Graphics g, JComponent c) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                        RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(bgColor);
                g2.fillRoundRect(0, 0, c.getWidth(), c.getHeight(), 10, 10);
                g2.dispose();
                super.paint(g, c);
            }
        });
    }

    /** 判断当前是否暗色主题 */
    private static boolean isDarkTheme() {
        return UIManager.getBoolean("ui.is.dark")
                || UIManager.getLookAndFeel().getName().toLowerCase().contains("dark")
                || UIManager.getColor("Panel.background") != null
                   && UIManager.getColor("Panel.background").getRed() < 100;
    }

    /** 根据 SVN 操作类型返回前景色（字体颜色） */
    private static Color getActionForeground(String action) {
        if (action == null) return null;
        boolean dark = isDarkTheme();
        switch (action.toUpperCase()) {
            case "A": return dark ? new Color(0x98C379) : new Color(0x2E7D32); // 新增 - 绿
            case "M": return dark ? new Color(0x61AFEF) : new Color(0x1565C0); // 修改 - 蓝
            case "D": return dark ? new Color(0xE06C75) : new Color(0xC62828); // 删除 - 红
            case "R": return dark ? new Color(0xE5C07B) : new Color(0xF57F17); // 替换 - 黄
            default:  return null;
        }
    }

    /** 变动文件表格的单元格渲染器，按操作类型设置字体颜色 */
    private static class ActionCellRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value,
                boolean isSelected, boolean hasFocus, int row, int column) {
            Component c = super.getTableCellRendererComponent(
                    table, value, isSelected, hasFocus, row, column);
            if (!isSelected) {
                String action = String.valueOf(table.getModel().getValueAt(row, 0));
                Color fg = getActionForeground(action);
                c.setForeground(fg != null ? fg : table.getForeground());
                c.setBackground(table.getBackground());
            }
            return c;
        }
    }

    /** 为对话框注册 Cmd+W / Ctrl+W 关闭快捷键 */
    private static void registerCloseShortcut(DialogWrapper dialogWrapper) {
        JRootPane rootPane = dialogWrapper.getRootPane();
        if (rootPane == null) return;
        int modifier = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
        KeyStroke closeKey = KeyStroke.getKeyStroke(KeyEvent.VK_W, modifier);
        rootPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(closeKey, "closeDialog");
        rootPane.getActionMap().put("closeDialog", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                dialogWrapper.close(DialogWrapper.CANCEL_EXIT_CODE);
            }
        });
    }

    /** 为 JDialog 注册 Cmd+W / Ctrl+W 关闭快捷键 */
    private static void registerCloseShortcut(JDialog dialog) {
        JRootPane rootPane = dialog.getRootPane();
        int modifier = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
        KeyStroke closeKey = KeyStroke.getKeyStroke(KeyEvent.VK_W, modifier);
        rootPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(closeKey, "closeDialog");
        rootPane.getActionMap().put("closeDialog", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                dialog.dispose();
            }
        });
    }

    /** 创建底部关闭按钮面板 */
    private static JPanel createCloseButtonPanel(DialogWrapper dialogWrapper) {
        JButton closeBtn = new JButton("关闭");
        closeBtn.addActionListener(e -> dialogWrapper.close(DialogWrapper.CANCEL_EXIT_CODE));
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 6));
        panel.add(closeBtn);
        return panel;
    }

    /**
     * 树节点携带的数据：文件叶子节点记录操作类型和相对路径
     */
    private static class FileNodeData {
        final String action;       // 操作类型：A/M/D/R
        final String displayName;  // 显示名称（文件名或目录名）
        final String relativePath; // 完整相对路径，用于调用 diff

        FileNodeData(String action, String displayName, String relativePath) {
            this.action = action;
            this.displayName = displayName;
            this.relativePath = relativePath;
        }

        @Override
        public String toString() {
            return displayName;
        }
    }

    /** 显示变动文件列表对话框（树状结构） */
    private void showChangedFilesDialog(SvnCommandExecutor.LogEntry entry, String branchUrl) {
        String pathPrefix = extractPathPrefix(branchUrl);

        // 解析所有条目
        List<String[]> allEntries = new ArrayList<>();
        Set<String> allPaths = new HashSet<>();
        for (String file : entry.changedFiles) {
            String trimmed = file.trim();
            int spaceIdx = trimmed.indexOf(' ');
            String action;
            String path;
            if (spaceIdx > 0) {
                action = trimmed.substring(0, spaceIdx).trim();
                path = trimmed.substring(spaceIdx + 1).trim();
            } else {
                action = "";
                path = trimmed;
            }
            if (!pathPrefix.isEmpty() && path.startsWith(pathPrefix)) {
                path = path.substring(pathPrefix.length());
                if (path.startsWith("/")) path = path.substring(1);
            }
            if (path.isEmpty()) continue;
            allEntries.add(new String[]{action, path});
            allPaths.add(path);
        }

        // 过滤：目录条目仅在新增/删除时展示，修改的目录跳过
        List<String[]> filteredEntries = new ArrayList<>();
        for (String[] ae : allEntries) {
            String action = ae[0];
            String path = ae[1];
            boolean isDir = path.endsWith("/");
            if (!isDir) {
                String dirPrefix = path + "/";
                for (String other : allPaths) {
                    if (other.startsWith(dirPrefix)) {
                        isDir = true;
                        break;
                    }
                }
            }
            if (isDir) {
                String upper = action.toUpperCase();
                if (!"A".equals(upper) && !"D".equals(upper) && !"R".equals(upper)) continue;
            }
            filteredEntries.add(ae);
        }

        // 从分支 URL 提取分支名作为根节点
        String branchName = branchUrl.replaceAll("/+$", "");
        int lastSlash = branchName.lastIndexOf('/');
        if (lastSlash >= 0) branchName = branchName.substring(lastSlash + 1);

        // 构建树
        DefaultMutableTreeNode root = new DefaultMutableTreeNode(branchName);
        // 目录节点缓存：路径 -> 节点
        Map<String, DefaultMutableTreeNode> dirNodes = new HashMap<>();

        for (String[] ae : filteredEntries) {
            String action = ae[0];
            String path = ae[1];
            // 去除尾部斜杠
            if (path.endsWith("/")) path = path.substring(0, path.length() - 1);

            String[] segments = path.split("/");
            DefaultMutableTreeNode parent = root;
            StringBuilder currentPath = new StringBuilder();

            for (int i = 0; i < segments.length; i++) {
                if (currentPath.length() > 0) currentPath.append("/");
                currentPath.append(segments[i]);
                String key = currentPath.toString();

                if (i < segments.length - 1) {
                    // 中间目录节点
                    DefaultMutableTreeNode dirNode = dirNodes.get(key);
                    if (dirNode == null) {
                        dirNode = new DefaultMutableTreeNode(segments[i]);
                        dirNodes.put(key, dirNode);
                        parent.add(dirNode);
                    }
                    parent = dirNode;
                } else {
                    // 叶子节点（文件或目录条目）
                    FileNodeData data = new FileNodeData(action, segments[i], ae[1]);
                    DefaultMutableTreeNode fileNode = new DefaultMutableTreeNode(data);
                    parent.add(fileNode);
                }
            }
        }

        // 压缩只有单个子目录的中间节点，合并为 "a/b/c" 形式
        compactSingleChildDirs(root);

        JTree fileTree = new JTree(new DefaultTreeModel(root));
        fileTree.setRootVisible(true);
        fileTree.setShowsRootHandles(true);
        fileTree.setRowHeight(22);

        // 自定义渲染器：修复背景色统一问题
        fileTree.setCellRenderer(new DefaultTreeCellRenderer() {
            {
                // 让未选中时的背景色跟随树背景，避免白色底色
                setBackgroundNonSelectionColor(null);
            }

            @Override
            public Component getTreeCellRendererComponent(JTree tree, Object value,
                    boolean sel, boolean expanded, boolean leaf, int row, boolean hasFocus) {
                super.getTreeCellRendererComponent(tree, value, sel, expanded, leaf, row, hasFocus);
                setBackgroundNonSelectionColor(tree.getBackground());
                DefaultMutableTreeNode node = (DefaultMutableTreeNode) value;
                Object userObj = node.getUserObject();

                if (userObj instanceof FileNodeData) {
                    FileNodeData data = (FileNodeData) userObj;
                    String actionTag = data.action.isEmpty() ? "" : "[" + data.action.toUpperCase() + "] ";
                    setText(actionTag + data.displayName);
                    setIcon(AllIcons.FileTypes.Any_type);
                    if (!sel) {
                        Color fg = getActionForeground(data.action);
                        if (fg != null) setForeground(fg);
                    }
                } else {
                    // 目录节点或根节点
                    setIcon(AllIcons.Nodes.Folder);
                }
                return this;
            }
        });

        // 展开所有节点
        for (int i = 0; i < fileTree.getRowCount(); i++) {
            fileTree.expandRow(i);
        }

        DialogWrapper dialogWrapper = new DialogWrapper(project, true) {
            {
                setTitle("r" + entry.revision + " 变动文件");
                setModal(false);
                init();
            }

            @Override
            protected JComponent createCenterPanel() {
                JBScrollPane scrollPane = new JBScrollPane(fileTree);
                scrollPane.setPreferredSize(new Dimension(600, 500));
                scrollPane.setMinimumSize(new Dimension(400, 250));
                return scrollPane;
            }

            @Override
            protected Action[] createActions() {
                // 不显示默认的 OK/Cancel 按钮
                return new Action[0];
            }

            @Override
            protected JComponent createSouthPanel() {
                return createCloseButtonPanel(this);
            }
        };

        // 双击叶子节点打开 diff
        fileTree.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    int row = fileTree.getRowForLocation(e.getX(), e.getY());
                    if (row < 0) {
                        // 点击在行内空白区域，用 closestRow 兜底
                        row = fileTree.getClosestRowForLocation(e.getX(), e.getY());
                        if (row < 0) return;
                        Rectangle rowBounds = fileTree.getRowBounds(row);
                        if (rowBounds == null || !rowBounds.contains(rowBounds.x, e.getY())) return;
                    }
                    TreePath treePath = fileTree.getPathForRow(row);
                    if (treePath == null) return;
                    DefaultMutableTreeNode node =
                            (DefaultMutableTreeNode) treePath.getLastPathComponent();
                    if (node.getUserObject() instanceof FileNodeData) {
                        FileNodeData data = (FileNodeData) node.getUserObject();
                        showDiffDialog(entry.revision, branchUrl, data.relativePath, data.action,
                                dialogWrapper.getWindow());
                    }
                }
            }
        });

        registerCloseShortcut(dialogWrapper);
        dialogWrapper.show();
    }

    /**
     * 压缩只有单个子目录的中间目录节点，将其合并显示为 "a/b/c" 形式。
     * 递归处理所有子节点。
     */
    private static void compactSingleChildDirs(DefaultMutableTreeNode node) {
        // 先递归处理子节点（从后往前，因为可能会移除子节点）
        for (int i = node.getChildCount() - 1; i >= 0; i--) {
            compactSingleChildDirs((DefaultMutableTreeNode) node.getChildAt(i));
        }
        // 当前节点是目录（userObject 为 String），且只有一个子节点且子节点也是目录
        while (node.getChildCount() == 1) {
            DefaultMutableTreeNode child = (DefaultMutableTreeNode) node.getChildAt(0);
            if (child.getUserObject() instanceof FileNodeData) break; // 子节点是文件，不合并
            // 合并：将当前节点名与子节点名用 / 拼接
            String merged = node.getUserObject().toString() + "/" + child.getUserObject().toString();
            node.setUserObject(merged);
            // 把子节点的所有子节点移到当前节点
            node.remove(0);
            while (child.getChildCount() > 0) {
                node.add((DefaultMutableTreeNode) child.getChildAt(0));
            }
        }
    }

    /** 使用 IDEA 内置 Diff Viewer 显示单个文件的变更 */
    private void showDiffDialog(String revision, String branchUrl, String filePath, String action,
            Window ownerWindow) {
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            long revNum = Long.parseLong(revision);
            String oldRevision = String.valueOf(revNum - 1);

            String upperAction = action != null ? action.toUpperCase() : "M";

            // 新增文件：旧版本为空；删除文件：新版本为空
            String oldContent;
            String newContent;
            if ("A".equals(upperAction)) {
                oldContent = "";
                newContent = executor.queryFileContent(branchUrl, revision, filePath);
                if (newContent == null) newContent = "";
            } else if ("D".equals(upperAction)) {
                oldContent = executor.queryFileContent(branchUrl, oldRevision, filePath);
                if (oldContent == null) oldContent = "";
                newContent = "";
            } else {
                oldContent = executor.queryFileContent(branchUrl, oldRevision, filePath);
                if (oldContent == null) oldContent = "";
                newContent = executor.queryFileContent(branchUrl, revision, filePath);
                if (newContent == null) newContent = "";
            }

            String finalOldContent = oldContent;
            String finalNewContent = newContent;
            ApplicationManager.getApplication().invokeLater(() -> {
                com.intellij.openapi.fileTypes.FileType fileType =
                        com.intellij.openapi.fileTypes.FileTypeManager.getInstance()
                                .getFileTypeByFileName(filePath);

                com.intellij.diff.contents.DiffContent leftContent =
                        com.intellij.diff.DiffContentFactory.getInstance()
                                .create(project, finalOldContent, fileType);
                com.intellij.diff.contents.DiffContent rightContent =
                        com.intellij.diff.DiffContentFactory.getInstance()
                                .create(project, finalNewContent, fileType);

                com.intellij.diff.requests.SimpleDiffRequest request =
                        new com.intellij.diff.requests.SimpleDiffRequest(
                                "r" + revision + " " + filePath,
                                leftContent, rightContent,
                                "r" + oldRevision, "r" + revision);

                com.intellij.diff.DiffDialogHints hints = new com.intellij.diff.DiffDialogHints(
                        com.intellij.openapi.ui.WindowWrapper.Mode.NON_MODAL,
                        ownerWindow,
                        wrapper -> com.intellij.openapi.util.Disposer.register(wrapper, () ->
                                {
                                    if (ownerWindow == null || !ownerWindow.isShowing()) return;
                                    if (!ownerWindow.requestFocusInWindow()) {
                                        ownerWindow.requestFocus();
                                    }
                                }));
                com.intellij.diff.DiffManager.getInstance().showDiff(project, request, hints);
            });
        });
    }

    /** 弹出版本号编辑对话框 */
    private void showRevisionsEditDialog() {
        Window ideWindow = SwingUtilities.getWindowAncestor(this);
        JDialog dialog = new JDialog(ideWindow, "版本号", Dialog.ModalityType.APPLICATION_MODAL);

        JPanel contentPane = new JPanel(new BorderLayout(0, 6));
        contentPane.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        JLabel hint = new JLabel("版本号（一行一个）:");
        contentPane.add(hint, BorderLayout.NORTH);

        JBTextArea editArea = new JBTextArea(15, 0);
        editArea.setLineWrap(true);
        editArea.setWrapStyleWord(true);
        // 输入过滤：只允许数字和换行符
        ((javax.swing.text.AbstractDocument) editArea.getDocument()).setDocumentFilter(
                new javax.swing.text.DocumentFilter() {
                    private String filter(String text) {
                        if (text == null) return null;
                        StringBuilder sb = new StringBuilder(text.length());
                        for (char c : text.toCharArray()) {
                            if (Character.isDigit(c) || c == '\n') {
                                sb.append(c);
                            }
                        }
                        return sb.toString();
                    }

                    @Override
                    public void insertString(FilterBypass fb, int offset, String string,
                            javax.swing.text.AttributeSet attr) throws javax.swing.text.BadLocationException {
                        super.insertString(fb, offset, filter(string), attr);
                    }

                    @Override
                    public void replace(FilterBypass fb, int offset, int length, String text,
                            javax.swing.text.AttributeSet attrs) throws javax.swing.text.BadLocationException {
                        super.replace(fb, offset, length, filter(text), attrs);
                    }
                });

        // 回填：把主界面的顿号分隔内容还原为一行一个
        String current = revisionsField.getText().trim();
        if (!current.isEmpty()) {
            editArea.setText(current.replace("、", "\n"));
        }

        JBScrollPane scrollPane = new JBScrollPane(editArea);
        contentPane.add(scrollPane, BorderLayout.CENTER);

        JButton confirmBtn = new JButton("确定");
        confirmBtn.addActionListener(e -> {
            // 解析、去重、拼接
            List<String> revs = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (String line : editArea.getText().split("\n")) {
                String rev = line.trim().replaceAll("[^0-9]", "");
                if (!rev.isEmpty() && seen.add(rev)) {
                    revs.add(rev);
                }
            }
            revisionsField.setText(String.join("、", revs));
            queryDone = false;
            dialog.dispose();
        });
        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 4));
        btnPanel.add(confirmBtn);
        contentPane.add(btnPanel, BorderLayout.SOUTH);

        dialog.setContentPane(contentPane);
        dialog.setSize(280, 420);
        dialog.setMinimumSize(new Dimension(200, 250));
        dialog.setLocationRelativeTo(ideWindow);
        registerCloseShortcut(dialog);
        dialog.setVisible(true);
    }

    private List<String> parseRevisions() {
        List<String> revisions = new ArrayList<>();
        String text = revisionsField.getText().trim();
        if (text.isEmpty()) return revisions;
        // 支持顿号分隔
        for (String part : text.split("[、]")) {
            String rev = part.trim().replaceAll("[^0-9]", "");
            if (!rev.isEmpty()) {
                revisions.add(rev);
            }
        }
        return revisions;
    }

    private static long parseRevisionNumber(String revision) {
        if (revision == null) return Long.MAX_VALUE;
        try {
            return Long.parseLong(revision.trim());
        } catch (NumberFormatException ignored) {
            return Long.MAX_VALUE;
        }
    }

    private String getBranchUrl() {
        String url = branchUrlField.getText().trim();
        PropertiesComponent.getInstance(project).setValue(BRANCH_URL_KEY, url);
        return url;
    }

    /**
     * 从 svn URL 中提取路径部分，用于去掉变动文件的前缀
     * 例如 svn://192.168.40.10/develop/helipay_online/branches/20230512/qa_20230512_default
     * svn 输出中路径为 /helipay_online/branches/20230512/qa_20230512_default/...
     * 需要找到 URL 中 host 后面的路径，再去掉第一段（仓库名 develop）
     */
    private String extractPathPrefix(String branchUrl) {
        try {
            // svn://host/develop/helipay_online/... -> /develop/helipay_online/...
            java.net.URI uri = new java.net.URI(branchUrl);
            String path = uri.getPath(); // /develop/helipay_online/branches/.../qa_xxx
            if (path == null || path.isEmpty()) return "";
            // svn log 输出的路径不包含仓库根名（第一段），去掉它
            // /develop/helipay_online/... -> /helipay_online/...
            if (path.startsWith("/")) path = path.substring(1);
            int slash = path.indexOf('/');
            if (slash < 0) return "";
            return "/" + path.substring(slash + 1);
        } catch (Exception e) {
            return "";
        }
    }

    /** 版本范围单段的合法模式：纯数字 或 HEAD 或 HEAD[+-]数字 */
    private static final java.util.regex.Pattern REVISION_PART_PATTERN =
            java.util.regex.Pattern.compile("^(\\d+|HEAD(\\s*[+-]\\s*\\d+)?)$", java.util.regex.Pattern.CASE_INSENSITIVE);

    /**
     * 校验版本范围格式。
     * 合法格式：单段（如 HEAD、HEAD-1000、12345）或用冒号分隔的两段（如 HEAD:HEAD-1000、1000:2000）
     */
    private static boolean isValidRevisionRange(String range) {
        if (range == null || range.trim().isEmpty()) return false;
        String[] parts = range.split(":");
        if (parts.length < 1 || parts.length > 2) return false;
        for (String part : parts) {
            if (!REVISION_PART_PATTERN.matcher(part.trim()).matches()) return false;
        }
        return true;
    }


    private void doLoad() {
        if (operating) return;
        unmergedFilterCheckBox.setSelected(false);
        String branchUrl = getBranchUrl();
        String workingDir = project.getBasePath();

        if (branchUrl.isEmpty()) {
            showFloatingTip(loadButton, "请输入源分支地址");
            return;
        }

        String limitStr = loadLimitField.getText().trim();
        int limit = limitStr.isEmpty() ? 30 : Integer.parseInt(limitStr);
        if (limit <= 0) limit = 30;
        if (limit > 500) limit = 500;

        setButtonsEnabled(false);
        outputArea.setText("正在加载...");
        logTableModel.setRowCount(0);
        logEntries.clear();

        int finalLimit = limit;
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            SvnCommandExecutor.SearchResult searchResult = executor.queryLatestLog(branchUrl, finalLimit);

            if (!searchResult.isSuccess()) {
                SwingUtilities.invokeLater(() -> {
                    outputArea.setText(searchResult.error);
                    setButtonsEnabled(true);
                });
                return;
            }

            List<SvnCommandExecutor.LogEntry> entries = searchResult.entries;

            // 查询合并状态
            List<String> revisions = new ArrayList<>();
            for (SvnCommandExecutor.LogEntry entry : entries) {
                if (!entry.revision.isEmpty()) {
                    revisions.add(entry.revision);
                }
            }
            Set<String> mergedRevs = (workingDir != null && !revisions.isEmpty())
                    ? executor.queryMergedRevisions(workingDir, branchUrl, revisions)
                    : java.util.Collections.emptySet();
            for (SvnCommandExecutor.LogEntry entry : entries) {
                if (mergedRevs.contains(entry.revision)) {
                    entry.merged = true;
                }
            }

            SwingUtilities.invokeLater(() -> {
                logEntries.clear();
                logEntries.addAll(entries);
                logTableModel.setRowCount(0);
                for (SvnCommandExecutor.LogEntry entry : entries) {
                    logTableModel.addRow(new Object[]{
                            false,
                            entry.revision,
                            entry.author,
                            displayMessage(entry.message),
                            formatCommitTime(entry.commitTime),
                            entry.merged ? "已合并" : "未合并"
                    });
                }
                outputArea.setText("加载完成，共 " + entries.size() + " 条记录");
                queryDone = true;
                setButtonsEnabled(true);
            });
        });
    }

    /** 筛选提交信息表格，仅显示/全部显示 */
    private void applyUnmergedFilter() {
        // 保存当前表格中各行的勾选状态（按版本号映射）
        Map<String, Boolean> checkedMap = new HashMap<>();
        for (int i = 0; i < logTableModel.getRowCount(); i++) {
            String rev = String.valueOf(logTableModel.getValueAt(i, 1));
            Boolean checked = (Boolean) logTableModel.getValueAt(i, 0);
            checkedMap.put(rev, checked);
        }

        boolean filterUnmerged = unmergedFilterCheckBox.isSelected();
        logTableModel.setRowCount(0);
        for (SvnCommandExecutor.LogEntry entry : logEntries) {
            if (filterUnmerged && entry.merged) continue;
            Boolean checked = checkedMap.getOrDefault(entry.revision, false);
            logTableModel.addRow(new Object[]{
                    checked,
                    entry.revision,
                    entry.author,
                    displayMessage(entry.message),
                    formatCommitTime(entry.commitTime),
                    entry.merged ? "已合并" : "未合并"
            });
        }
    }

    private void doQuery(boolean append) {
        if (operating) return;
        unmergedFilterCheckBox.setSelected(false);
        String branchUrl = getBranchUrl();
        List<String> revisions = parseRevisions();
        String workingDir = project.getBasePath();

        if (branchUrl.isEmpty()) {
            showFloatingTip(queryButton, "请输入源分支地址");
            return;
        }
        if (revisions.isEmpty()) {
            showFloatingTip(queryButton, "请输入版本号");
            return;
        }

        // 去重并回写到输入框
        List<String> unique = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String rev : revisions) {
            if (seen.add(rev)) {
                unique.add(rev);
            }
        }
        revisionsField.setText(String.join("、", unique));
        List<String> finalRevisions = unique;

        setButtonsEnabled(false);
        outputArea.setText("正在查询...");
        if (!append) {
            logTableModel.setRowCount(0);
            logEntries.clear();
        }

        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            Set<String> mergedRevs = (workingDir != null)
                    ? executor.queryMergedRevisions(workingDir, branchUrl, finalRevisions)
                    : java.util.Collections.emptySet();

            List<SvnCommandExecutor.LogEntry> entries = new ArrayList<>();
            for (String rev : finalRevisions) {
                SvnCommandExecutor.LogEntry entry = executor.queryLogVerbose(branchUrl, rev);
                if (entry == null) {
                    continue;
                }
                if (mergedRevs.contains(rev)) {
                    entry.merged = true;
                }
                entries.add(entry);
            }

            SwingUtilities.invokeLater(() -> {
                if (append) {
                    appendEntriesDedup(entries);
                } else {
                    logEntries.clear();
                    logEntries.addAll(entries);
                    logTableModel.setRowCount(0);
                    for (SvnCommandExecutor.LogEntry entry : entries) {
                        logTableModel.addRow(new Object[]{
                                true,
                                entry.revision,
                                entry.author,
                                displayMessage(entry.message),
                                formatCommitTime(entry.commitTime),
                                entry.merged ? "已合并" : "未合并"
                        });
                    }
                }
                outputArea.setText("查询完成");
                queryDone = true;
                setButtonsEnabled(true);
            });
        });
    }

    private void doSearch() {
        if (operating) return;
        unmergedFilterCheckBox.setSelected(false);
        String branchUrl = getBranchUrl();
        String keyword = keywordField.getText().trim();
        String author = authorField.getText().trim();
        String limitText = searchLimitField.getText().trim();
        String workingDir = project.getBasePath();

        if (branchUrl.isEmpty()) {
            showFloatingTip(searchButton, "请输入源分支地址");
            return;
        }
        if (keyword.isEmpty() && author.isEmpty()) {
            showFloatingTip(searchButton, "关键字和作者不能同时为空");
            return;
        }
        if (limitText.isEmpty()) {
            showFloatingTip(searchButton, "请输入版本范围");
            return;
        }
        // 校验版本范围格式：每段为纯数字或 HEAD([+-]数字)?
        if (!isValidRevisionRange(limitText)) {
            showFloatingTip(searchButton, "版本范围格式不正确");
            return;
        }

        setButtonsEnabled(false);
        outputArea.setText("正在搜索...");
        logTableModel.setRowCount(0);
        logEntries.clear();

        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            SvnCommandExecutor.SearchResult searchResult = executor.queryLogBySearch(
                    branchUrl, keyword, author, limitText);

            if (!searchResult.isSuccess()) {
                SwingUtilities.invokeLater(() -> {
                    outputArea.setText(searchResult.error);
                    setButtonsEnabled(true);
                });
                return;
            }

            List<SvnCommandExecutor.LogEntry> entries = searchResult.entries;

            // 查询合并状态
            List<String> revisions = new ArrayList<>();
            for (SvnCommandExecutor.LogEntry entry : entries) {
                if (!entry.revision.isEmpty()) {
                    revisions.add(entry.revision);
                }
            }
            Set<String> mergedRevs = (workingDir != null && !revisions.isEmpty())
                    ? executor.queryMergedRevisions(workingDir, branchUrl, revisions)
                    : java.util.Collections.emptySet();
            for (SvnCommandExecutor.LogEntry entry : entries) {
                if (mergedRevs.contains(entry.revision)) {
                    entry.merged = true;
                }
            }

            SwingUtilities.invokeLater(() -> {
                logEntries.clear();
                logEntries.addAll(entries);
                logTableModel.setRowCount(0);
                for (SvnCommandExecutor.LogEntry entry : entries) {
                    logTableModel.addRow(new Object[]{
                            false,
                            entry.revision,
                            entry.author,
                            displayMessage(entry.message),
                            formatCommitTime(entry.commitTime),
                            entry.merged ? "已合并" : "未合并"
                    });
                }
                outputArea.setText("搜索完成，共 " + entries.size() + " 条记录"
                        + (entries.size() >= 30 ? "（已达上限）" : ""));
                queryDone = true;
                setButtonsEnabled(true);
            });
        });
    }

    private void doMerge() {
        if (operating) return;
        if (!queryDone) {
            showFloatingTip(mergeButton, "请先点击查询再合并");
            return;
        }

        String branchUrl = getBranchUrl();
        String workingDir = project.getBasePath();

        // 从表格中提取已勾选且未合并的版本号
        List<String> revisions = new ArrayList<>();
        for (int i = 0; i < logTableModel.getRowCount(); i++) {
            Boolean checked = (Boolean) logTableModel.getValueAt(i, 0);
            String status = String.valueOf(logTableModel.getValueAt(i, 4));
            if (Boolean.TRUE.equals(checked) && "未合并".equals(status)) {
                String rev = String.valueOf(logTableModel.getValueAt(i, 1));
                if (!rev.isEmpty()) {
                    revisions.add(rev);
                }
            }
        }

        if (branchUrl.isEmpty()) {
            showFloatingTip(mergeButton, "请输入源分支地址");
            return;
        }
        if (revisions.isEmpty()) {
            outputArea.setText("没有已勾选的未合并版本号");
            return;
        }
        if (workingDir == null) {
            outputArea.setText("无法获取项目根目录");
            return;
        }

        // 弹窗确认
        int count = revisions.size();
        JPanel confirmPanel = new JPanel(new BorderLayout());
        JLabel confirmLabel = new JLabel("确定合并这 " + count + " 个提交吗？", SwingConstants.CENTER);
        confirmLabel.setBorder(BorderFactory.createEmptyBorder(12, 20, 12, 20));
        confirmPanel.add(confirmLabel, BorderLayout.CENTER);

        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 6));
        JButton confirmBtn = new JButton("确认");
        JButton cancelBtn = new JButton("取消");
        btnPanel.add(confirmBtn);
        btnPanel.add(cancelBtn);
        confirmPanel.add(btnPanel, BorderLayout.SOUTH);

        Window ideWindow = SwingUtilities.getWindowAncestor(this);
        JDialog confirmDialog = new JDialog(ideWindow, "合并确认", Dialog.ModalityType.APPLICATION_MODAL);
        confirmDialog.setContentPane(confirmPanel);
        confirmDialog.pack();
        confirmDialog.setResizable(false);

        // 显示在合并按钮上方
        Point btnPos = SwingUtilities.convertPoint(mergeButton, 0, 0, null);
        int dx = btnPos.x + (mergeButton.getWidth() - confirmDialog.getWidth()) / 2;
        int dy = btnPos.y - confirmDialog.getHeight() - 4;
        if (dy < 0) dy = btnPos.y + mergeButton.getHeight() + 4;
        confirmDialog.setLocation(dx, dy);

        cancelBtn.addActionListener(ev -> confirmDialog.dispose());
        confirmBtn.addActionListener(ev -> {
            confirmDialog.dispose();
            executeMerge(branchUrl, workingDir, revisions);
        });

        confirmDialog.setVisible(true);
    }

    /** 执行实际的合并操作 */
    private void executeMerge(String branchUrl, String workingDir, List<String> revisions) {
        setButtonsEnabled(false);
        outputArea.setText("正在合并...");

        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            if (isProjectDisposedSafe()) return;
            Set<String> mergedRevs = executor.queryMergedRevisions(workingDir, branchUrl, revisions);
            List<String> toMerge = new ArrayList<>();
            for (String rev : revisions) {
                if (!mergedRevs.contains(rev)) {
                    toMerge.add(rev);
                }
            }
            toMerge.sort((a, b) -> {
                long aNum = parseRevisionNumber(a);
                long bNum = parseRevisionNumber(b);
                if (aNum != bNum) {
                    return Long.compare(aNum, bNum);
                }
                return String.valueOf(a).compareTo(String.valueOf(b));
            });

            if (toMerge.isEmpty()) {
                runOnUiThreadIfProjectAlive(() -> {
                    outputArea.setText("");
                    setButtonsEnabled(true);
                    JOptionPane.showMessageDialog(
                            SvnMergeToolWindowPanel.this,
                            "没有可以合并的版本号", "提示",
                            JOptionPane.INFORMATION_MESSAGE);
                });
                return;
            }

            boolean allSuccess = true;
            Set<String> mergedSuccessRevs = new java.util.LinkedHashSet<>();

            appendOutputLineRealtime("开始执行 svn update...");
            SvnCommandExecutor.Result updateResult = executor.update(workingDir, line -> {
                String decodedLine = SvnCommandExecutor.decodeUnicodeEscapes(line);
                if (decodedLine == null || decodedLine.trim().isEmpty()) return;
                appendOutputLineRealtime(decodedLine);
            });
            if (!updateResult.isSuccess()) {
                String updateError = SvnCommandExecutor.decodeUnicodeEscapes(updateResult.stderr);
                StringBuilder updateFailLine = new StringBuilder("svn update 失败");
                if (updateError != null && !updateError.trim().isEmpty()) {
                    updateFailLine.append("：").append(updateError.trim());
                }
                appendOutputLineRealtime(updateFailLine.toString());
                allSuccess = false;
            } else {
                appendOutputLineRealtime("svn update 完成");
            }

            appendOutputLineRealtime("svn merge 开始");
            if (allSuccess) {
                for (int ri = 0; ri < toMerge.size(); ri++) {
                    String rev = toMerge.get(ri);
                    appendOutputLineRealtime("开始合并 r" + rev + "（" + (ri + 1) + "/" + toMerge.size() + "）...");
                    List<String> conflictLines = new java.util.concurrent.CopyOnWriteArrayList<>();
                    SvnCommandExecutor.Result result = executor.merge(workingDir, branchUrl, rev, line -> {
                        String decodedLine = SvnCommandExecutor.decodeUnicodeEscapes(line);
                        if (decodedLine == null || decodedLine.trim().isEmpty()) return;
                        appendOutputLineRealtime(decodedLine);
                        // 检测冲突：svn merge 输出中以 "C " 开头表示内容冲突
                        String trimmed = decodedLine.trim();
                        if (trimmed.matches("^C\\s+.*") || trimmed.matches("^TC\\s+.*")) {
                            conflictLines.add(trimmed);
                        }
                    });
                    if (result.isSuccess() && conflictLines.isEmpty()) {
                        appendOutputLineRealtime("r" + rev + " 合并成功");
                        mergedSuccessRevs.add(rev);
                    } else if (!conflictLines.isEmpty()) {
                        // 发现冲突，暂停后续合并
                        appendOutputLineRealtime("r" + rev + " 合并产生冲突，共 " + conflictLines.size() + " 个冲突文件：");
                        for (String cl : conflictLines) {
                            appendOutputLineRealtime("  冲突：" + cl);
                        }
                        mergedSuccessRevs.add(rev);
                        int remainCount = toMerge.size() - ri - 1;
                        if (remainCount > 0) {
                            appendOutputLineRealtime("暂停合并，请先解决冲突。剩余 " + remainCount + " 个版本待合并");
                        } else {
                            appendOutputLineRealtime("请先解决冲突后再提交");
                        }
                        // 刷新 VCS 变更列表，然后弹出冲突解决窗口
                        java.util.concurrent.CountDownLatch conflictLatch = new java.util.concurrent.CountDownLatch(1);
                        java.util.concurrent.atomic.AtomicBoolean userChoseContinue = new java.util.concurrent.atomic.AtomicBoolean(false);
                        runOnUiThreadIfProjectAlive(() -> {
                            try {
                                refreshVcsChanges(() -> {
                                    // 刷新完成后弹出冲突解决窗口
                                    openResolveConflictsDialog();
                                }, "合并冲突后刷新变更列表");
                            } catch (Exception e) {
                                appendOutputLineRealtime("刷新变更列表失败：" + e.getMessage());
                            }
                            // 延迟弹出非模态确认对话框，让冲突解决窗口先显示
                            com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater(() -> {
                                showConflictContinueDialog(userChoseContinue, conflictLatch);
                            });
                        });
                        // 等待用户解决冲突
                        try {
                            conflictLatch.await();
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            appendOutputLineRealtime("等待冲突解决被中断");
                            allSuccess = false;
                            break;
                        }
                        // 检查用户是否选择了停止合并
                        if (!userChoseContinue.get()) {
                            appendOutputLineRealtime("用户选择停止合并");
                            allSuccess = false;
                            break;
                        }
                        // 检查冲突是否已全部解决
                        SvnCommandExecutor.Result statusResult = executor.status(workingDir);
                        boolean stillHasConflict = false;
                        if (statusResult.isSuccess() && statusResult.stdout != null) {
                            for (String statusLine : statusResult.stdout.split("\n")) {
                                String st = statusLine.trim();
                                if (st.startsWith("C ") || (st.length() > 6 && st.charAt(6) == 'C')) {
                                    stillHasConflict = true;
                                    break;
                                }
                            }
                        }
                        if (stillHasConflict) {
                            appendOutputLineRealtime("仍有未解决的冲突，停止后续合并");
                            allSuccess = false;
                            break;
                        }
                        appendOutputLineRealtime("冲突已解决，继续合并下一个版本");
                    } else {
                        String mergeError = SvnCommandExecutor.decodeUnicodeEscapes(result.stderr);
                        String failLine = "r" + rev + " 合并失败"
                                + (mergeError == null || mergeError.trim().isEmpty() ? "" : "：" + mergeError.trim());
                        appendOutputLineRealtime(failLine);
                        allSuccess = false;
                        break;
                    }
                }
            }

            boolean finalAllSuccess = allSuccess;

            List<String> skipped = new ArrayList<>();
            for (String rev : revisions) {
                if (mergedRevs.contains(rev)) {
                    skipped.add(rev);
                }
            }
            Set<String> finalMergedSuccessRevs = mergedSuccessRevs;

            runOnUiThreadIfProjectAlive(() -> {
                for (SvnCommandExecutor.LogEntry entry : logEntries) {
                    if (finalMergedSuccessRevs.contains(entry.revision)) {
                        entry.merged = true;
                    }
                }
                applyUnmergedFilter();
                if (!skipped.isEmpty()) {
                    outputArea.append("\n已跳过（已合并）：" + String.join(", ", skipped));
                }
                outputArea.putClientProperty("commitMessage", null);
                if (finalAllSuccess) {
                    StringBuilder commitMsg = new StringBuilder();
                    for (String rev : toMerge) {
                        SvnCommandExecutor.LogEntry entry = findEntryByRevision(rev);
                        if (entry != null) {
                            String displayMsg = displayMessage(entry.message);
                            if (displayMsg == null) displayMsg = "";
                            commitMsg.append(displayMsg.trim()).append(" | ")
                                    .append(entry.author).append(" | ")
                                    .append(entry.revision).append("\n");
                        }
                    }
                    String msg = commitMsg.toString().trim();
                    if (!msg.isEmpty()) {
                        Toolkit.getDefaultToolkit().getSystemClipboard()
                                .setContents(new StringSelection(msg), null);
                        outputArea.append("\n\n【已复制】建议的提交信息：\n" + msg);
                        outputArea.putClientProperty("commitMessage", msg);
                    }
                }
                if (!finalMergedSuccessRevs.isEmpty()) {
                    try {
                        refreshVcsChanges(() -> { }, "合并完成后刷新变更列表");
                    } catch (Exception refreshError) {
                        outputArea.append("\n刷新变更列表失败：" + refreshError.getMessage());
                    }
                }
                setButtonsEnabled(true);
            });
        });
    }

    /** 将新查询结果追加到提交信息列表，按版本号去重 */
    private void appendEntriesDedup(List<SvnCommandExecutor.LogEntry> newEntries) {
        Set<String> existingRevs = new HashSet<>();
        for (SvnCommandExecutor.LogEntry entry : logEntries) {
            existingRevs.add(entry.revision);
        }
        for (SvnCommandExecutor.LogEntry entry : newEntries) {
            if (existingRevs.add(entry.revision)) {
                logEntries.add(entry);
                logTableModel.addRow(new Object[]{
                        true,
                        entry.revision,
                        entry.author,
                        displayMessage(entry.message),
                        formatCommitTime(entry.commitTime),
                        entry.merged ? "已合并" : "未合并"
                });
            }
        }
    }

    /** 在指定组件下方显示浮动提示，不影响布局，2 秒后自动消失 */
    private void showFloatingTip(JComponent anchor, String message) {
        JLabel tipLabel = new JLabel(message) {
            @Override
            protected void paintComponent(java.awt.Graphics g) {
                java.awt.Graphics2D g2 = (java.awt.Graphics2D) g.create();
                g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,
                        java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(getBackground());
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 12, 12);
                g2.dispose();
                super.paintComponent(g);
            }

            @Override
            protected void paintBorder(java.awt.Graphics g) {
                java.awt.Graphics2D g2 = (java.awt.Graphics2D) g.create();
                g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,
                        java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(new Color(0x555555));
                g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 12, 12);
                g2.dispose();
            }
        };
        tipLabel.setOpaque(false);
        tipLabel.setBackground(new Color(0x333333));
        tipLabel.setForeground(new Color(0x5CB85C));
        tipLabel.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        tipLabel.setFont(tipLabel.getFont().deriveFont(Font.PLAIN, 12f));

        Dimension tipSize = tipLabel.getPreferredSize();
        tipLabel.setSize(tipSize);

        // 找到 layeredPane，把提示放在其上
        JRootPane rootPane = SwingUtilities.getRootPane(anchor);
        if (rootPane == null) return;
        JLayeredPane layeredPane = rootPane.getLayeredPane();

        // 计算 anchor 在 layeredPane 坐标系中的位置，提示显示在按钮下方
        Point anchorPos = SwingUtilities.convertPoint(anchor, 0, 0, layeredPane);
        int x = anchorPos.x + (anchor.getWidth() - tipSize.width) / 2;
        int y = anchorPos.y + anchor.getHeight() + 4;
        // 确保不超出左右边界
        x = Math.max(0, Math.min(x, layeredPane.getWidth() - tipSize.width));
        // 如果下方空间不足，则显示在上方
        if (y + tipSize.height > layeredPane.getHeight()) {
            y = anchorPos.y - tipSize.height - 4;
        }

        tipLabel.setLocation(x, y);
        layeredPane.add(tipLabel, JLayeredPane.POPUP_LAYER);
        layeredPane.repaint(x, y, tipSize.width, tipSize.height);

        Timer timer = new Timer(2000, e -> {
            layeredPane.remove(tipLabel);
            layeredPane.repaint(tipLabel.getX(), tipLabel.getY(),
                    tipLabel.getWidth(), tipLabel.getHeight());
        });
        timer.setRepeats(false);
        timer.start();
    }

    /** 打开 IDEA 的 Commit Changes 窗口 */
    private void doCommit() {
        if (isProjectDisposedSafe()) return;
        try {
            refreshVcsChanges(this::openCommitDialog, "刷新变更列表后打开提交窗口");
        } catch (Exception e) {
            outputArea.setText("打开 Commit 对话框失败：" + e.getMessage());
        }
    }

    private boolean isProjectDisposedSafe() {
        try {
            java.lang.reflect.Method isDisposedMethod = project.getClass().getMethod("isDisposed");
            Object result = isDisposedMethod.invoke(project);
            return result instanceof Boolean && (Boolean) result;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void runOnUiThreadIfProjectAlive(Runnable runnable) {
        ApplicationManager.getApplication().invokeLater(() -> {
            if (isProjectDisposedSafe()) return;
            runnable.run();
        });
    }

    private void appendOutputLineRealtime(String line) {
        runOnUiThreadIfProjectAlive(() -> {
            if (outputArea.getDocument().getLength() > 0) {
                outputArea.append("\n");
            }
            outputArea.append(line);
        });
    }

    private void refreshVcsChanges(Runnable afterUpdate, String updateTitle) {
        if (isProjectDisposedSafe()) return;
        // 外部 svn merge 后，先刷新 VFS 和变更列表，避免 IDEA 展示旧数据。
        Runnable callback = afterUpdate != null ? afterUpdate : () -> { };
        try {
            ApplicationManager.getApplication().runWriteAction(() -> {
                if (isProjectDisposedSafe()) return;
                FileDocumentManager.getInstance().saveAllDocuments();
                VirtualFileManager.getInstance().syncRefresh();
                VcsDirtyScopeManager.getInstance(project).markEverythingDirty();
            });
            if (isProjectDisposedSafe()) return;
            ChangeListManager.getInstance(project).invokeAfterUpdate(true, () -> {
                if (isProjectDisposedSafe()) return;
                callback.run();
            });
        } catch (Throwable t) {
            if (!isProjectDisposedSafe()) {
                throw t;
            }
        }
    }

    private void openCommitDialog() {
        if (isProjectDisposedSafe()) return;
        com.intellij.openapi.actionSystem.ActionManager actionManager =
                com.intellij.openapi.actionSystem.ActionManager.getInstance();
        com.intellij.openapi.actionSystem.AnAction action =
                actionManager.getAction("CheckinProject");
        if (action == null) {
            outputArea.setText("无法找到 Commit 操作");
            return;
        }
        actionManager.tryToExecute(action,
                null, this, com.intellij.openapi.actionSystem.ActionPlaces.UNKNOWN, true);
    }

    private void doRevert() {
        if (isProjectDisposedSafe()) return;
        com.intellij.openapi.actionSystem.ActionManager actionManager =
                com.intellij.openapi.actionSystem.ActionManager.getInstance();
        com.intellij.openapi.actionSystem.AnAction action =
                actionManager.getAction("ChangesView.Revert");
        if (action == null) {
            outputArea.setText("无法找到 Revert 操作");
            return;
        }
        actionManager.tryToExecute(action,
                null, this, com.intellij.openapi.actionSystem.ActionPlaces.UNKNOWN, true);
    }

    private void openResolveConflictsDialog() {
        if (isProjectDisposedSafe()) return;
        // 尝试多种方式激活 Local Changes / Commit 视图
        com.intellij.openapi.wm.ToolWindowManager twm =
                com.intellij.openapi.wm.ToolWindowManager.getInstance(project);
        // 尝试不同的 ToolWindow ID（新版 IDEA 用 Commit，旧版用 Version Control）
        String[] toolWindowIds = {"Commit", "Version Control", "Changes"};
        for (String id : toolWindowIds) {
            com.intellij.openapi.wm.ToolWindow tw = twm.getToolWindow(id);
            if (tw != null && tw.isAvailable()) {
                tw.activate(() -> {
                    // 激活后选中 Local Changes tab
                    com.intellij.ui.content.Content content = tw.getContentManager().findContent("Local Changes");
                    if (content != null) {
                        tw.getContentManager().setSelectedContent(content);
                    }
                });
                return;
            }
        }
    }

    /**
     * 在插件窗口正中间显示冲突等待面板（内嵌在插件窗口内部，使用 GlassPane）
     */
    private void showConflictContinueDialog(
            java.util.concurrent.atomic.AtomicBoolean userChoseContinue,
            java.util.concurrent.CountDownLatch latch) {
        this.conflictUserChoice = userChoseContinue;
        this.conflictLatch = latch;

        // 移除旧面板（如果存在）
        if (conflictWaitingPanel != null) {
            this.remove(conflictWaitingPanel);
        }

        // 创建悬浮面板
        JPanel innerPanel = new JPanel(new BorderLayout(0, 12));
        innerPanel.setBackground(new Color(0x3C3F41));
        innerPanel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(0xE6A23C), 2),
                BorderFactory.createEmptyBorder(16, 20, 16, 20)
        ));

        JLabel msgLabel = new JLabel(
                "<html><div style='text-align:center;'>"
                        + "<span style='color:#E6A23C;font-weight:bold;font-size:14px;'>发现冲突</span><br><br>"
                        + "请在 IDE 中解决所有冲突后，点击「继续合并」。</div></html>",
                SwingConstants.CENTER);
        msgLabel.setForeground(Color.WHITE);
        innerPanel.add(msgLabel, BorderLayout.CENTER);

        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 12, 0));
        btnPanel.setOpaque(false);
        JButton continueBtn = new JButton("继续合并");
        JButton stopBtn = new JButton("停止合并");
        styleButton(continueBtn, new Color(0x67C23A));
        styleButton(stopBtn, new Color(0x909399));
        continueBtn.setPreferredSize(new Dimension(90, 30));
        stopBtn.setPreferredSize(new Dimension(90, 30));
        btnPanel.add(stopBtn);
        btnPanel.add(continueBtn);
        innerPanel.add(btnPanel, BorderLayout.SOUTH);

        continueBtn.addActionListener(ev -> onConflictChoice(true));
        stopBtn.addActionListener(ev -> onConflictChoice(false));

        // 创建一个覆盖整个插件窗口的半透明遮罩层，内部居中显示面板
        conflictWaitingPanel = new JPanel(new GridBagLayout()) {
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                // 半透明背景遮罩
                g.setColor(new Color(0, 0, 0, 120));
                g.fillRect(0, 0, getWidth(), getHeight());
            }
        };
        conflictWaitingPanel.setOpaque(false);
        conflictWaitingPanel.add(innerPanel);

        // 使用 OverlayLayout 将遮罩层覆盖在内容之上
        this.setLayout(new OverlayLayout(this));
        // 先移除所有组件，重新添加
        Component[] components = this.getComponents();
        this.removeAll();
        // 先添加遮罩（会显示在最上层）
        this.add(conflictWaitingPanel);
        // 再添加原有内容
        for (Component comp : components) {
            if (comp != conflictWaitingPanel) {
                this.add(comp);
            }
        }
        this.revalidate();
        this.repaint();
    }

    private void onConflictChoice(boolean continueChoice) {
        if (conflictWaitingPanel != null) {
            // 移除遮罩层，恢复原来的 BorderLayout
            this.remove(conflictWaitingPanel);
            Component[] components = this.getComponents();
            this.removeAll();
            this.setLayout(new BorderLayout());
            for (Component comp : components) {
                this.add(comp, BorderLayout.CENTER);
                break; // 只有一个主内容组件
            }
            conflictWaitingPanel = null;
            this.revalidate();
            this.repaint();
        }
        if (conflictUserChoice != null) {
            conflictUserChoice.set(continueChoice);
        }
        if (conflictLatch != null) {
            conflictLatch.countDown();
        }
    }

    private void setButtonsEnabled(boolean enabled) {
        operating = !enabled;
    }

    private static JLabel createLabel(String text) {
        JLabel label = new JLabel(text);
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        label.setBorder(BorderFactory.createEmptyBorder(0, SECTION_LABEL_LEFT_PADDING, 0, 0));
        return label;
    }
}
