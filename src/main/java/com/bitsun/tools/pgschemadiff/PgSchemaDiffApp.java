package com.bitsun.tools.pgschemadiff;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.UIManager;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.RenderingHints;
import java.awt.Insets;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.prefs.Preferences;
import java.util.stream.Collectors;

/**
 * PG 表结构差异比对工具的 Swing 图形界面。
 * 填好 A、B 两库的连接信息和要比对的实例（schema）名，点"开始比对"，
 * 会给每个有差异的实例生成一份 {输出目录}/{schema}.sql，内容是可以直接在 B 库执行的
 * ALTER TABLE ... ADD COLUMN ... 语句。
 */
public class PgSchemaDiffApp extends JFrame {

    private final JTextField aHostField = new JTextField(30);
    private final JTextField aPortField = new JTextField("5432", 6);
    private final JTextField aDbField = new JTextField(20);
    private final JTextField aUserField = new JTextField(16);
    private final JPasswordField aPasswordField = new JPasswordField(16);
    private final JCheckBox aSslCheckBox = new JCheckBox("SSL（目标库要求 SSL 才勾选）", false);

    private final JTextField bHostField = new JTextField(30);
    private final JTextField bPortField = new JTextField("5432", 6);
    private final JTextField bDbField = new JTextField(20);
    private final JTextField bUserField = new JTextField(16);
    private final JPasswordField bPasswordField = new JPasswordField(16);
    private final JCheckBox bSslCheckBox = new JCheckBox("SSL（目标库要求 SSL 才勾选）", false);

    private final JTextField schemaNamesField = new JTextField(30);
    private final JTextField outputDirField = new JTextField("diff_reports", 30);

    private final JTextArea logArea = new JTextArea();
    private final JButton runButton = new JButton("开始比对");

    private final Preferences prefs = Preferences.userNodeForPackage(PgSchemaDiffApp.class);

    public PgSchemaDiffApp() {
        super("PG 表结构差异比对工具");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLayout(new BorderLayout(8, 8));

        JPanel dbRow = new JPanel(new GridBagLayout());
        GridBagConstraints aGbc = new GridBagConstraints();
        aGbc.gridx = 0;
        aGbc.weightx = 1.0;
        aGbc.fill = GridBagConstraints.BOTH;
        dbRow.add(buildDbPanel("A 库", aHostField, aPortField, aDbField, aUserField, aPasswordField, aSslCheckBox), aGbc);

        GridBagConstraints arrowGbc = new GridBagConstraints();
        arrowGbc.gridx = 1;
        arrowGbc.weightx = 0;
        arrowGbc.fill = GridBagConstraints.VERTICAL;
        arrowGbc.insets = new Insets(0, 4, 0, 4);
        dbRow.add(buildArrowPanel(), arrowGbc);

        GridBagConstraints bGbc = new GridBagConstraints();
        bGbc.gridx = 2;
        bGbc.weightx = 1.0;
        bGbc.fill = GridBagConstraints.BOTH;
        dbRow.add(buildDbPanel("B 库", bHostField, bPortField, bDbField, bUserField, bPasswordField, bSslCheckBox), bGbc);

        JPanel formPanel = new JPanel();
        formPanel.setLayout(new BoxLayout(formPanel, BoxLayout.Y_AXIS));
        formPanel.add(dbRow);
        formPanel.add(buildMiscPanel());

        logArea.setEditable(false);
        logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JScrollPane logScroll = new JScrollPane(logArea);
        logScroll.setPreferredSize(new Dimension(980, 420));

        runButton.addActionListener(e -> runDiff());
        JPanel bottomPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        bottomPanel.add(runButton);

        add(formPanel, BorderLayout.NORTH);
        add(logScroll, BorderLayout.CENTER);
        add(bottomPanel, BorderLayout.SOUTH);

        loadPrefs();
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                savePrefs();
            }
        });

        pack();
        setLocationRelativeTo(null);
    }

    /** 把上次填的连接信息（含密码）从本机用户配置里读回来，省得每次重填 */
    private void loadPrefs() {
        aHostField.setText(prefs.get("a.host", ""));
        aPortField.setText(prefs.get("a.port", "5432"));
        aDbField.setText(prefs.get("a.db", ""));
        aUserField.setText(prefs.get("a.user", ""));
        aPasswordField.setText(prefs.get("a.password", ""));
        aSslCheckBox.setSelected(prefs.getBoolean("a.ssl", false));

        bHostField.setText(prefs.get("b.host", ""));
        bPortField.setText(prefs.get("b.port", "5432"));
        bDbField.setText(prefs.get("b.db", ""));
        bUserField.setText(prefs.get("b.user", ""));
        bPasswordField.setText(prefs.get("b.password", ""));
        bSslCheckBox.setSelected(prefs.getBoolean("b.ssl", false));

        schemaNamesField.setText(prefs.get("schemaNames", ""));
        outputDirField.setText(prefs.get("outputDir", "diff_reports"));
    }

    /** 关窗口/开始比对时把当前填的内容存到本机用户配置（Java Preferences），下次打开自动带回来 */
    private void savePrefs() {
        prefs.put("a.host", aHostField.getText());
        prefs.put("a.port", aPortField.getText());
        prefs.put("a.db", aDbField.getText());
        prefs.put("a.user", aUserField.getText());
        prefs.put("a.password", new String(aPasswordField.getPassword()));
        prefs.putBoolean("a.ssl", aSslCheckBox.isSelected());

        prefs.put("b.host", bHostField.getText());
        prefs.put("b.port", bPortField.getText());
        prefs.put("b.db", bDbField.getText());
        prefs.put("b.user", bUserField.getText());
        prefs.put("b.password", new String(bPasswordField.getPassword()));
        prefs.putBoolean("b.ssl", bSslCheckBox.isSelected());

        prefs.put("schemaNames", schemaNamesField.getText());
        prefs.put("outputDir", outputDirField.getText());
    }

    /** A 库、B 库中间画一个箭头，标出比对方向（拿 A 补 B） */
    private JPanel buildArrowPanel() {
        JPanel panel = new JPanel() {
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(new Color(214, 39, 40));
                g2.setStroke(new BasicStroke(3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));

                int y = getHeight() / 2;
                int lineStartX = 4;
                int arrowTipX = getWidth() - 4;
                int arrowBackX = arrowTipX - 12;

                g2.drawLine(lineStartX, y, arrowBackX, y);
                int[] xs = {arrowTipX, arrowBackX, arrowBackX};
                int[] ys = {y, y - 7, y + 7};
                g2.fillPolygon(xs, ys, 3);
                g2.dispose();
            }
        };
        panel.setPreferredSize(new Dimension(36, 30));
        panel.setOpaque(false);
        return panel;
    }

    private JPanel buildDbPanel(String title, JTextField host, JTextField port, JTextField db,
                                 JTextField user, JPasswordField password, JCheckBox sslCheckBox) {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createTitledBorder(title));

        GridBagConstraints labelGbc = new GridBagConstraints();
        labelGbc.gridx = 0;
        labelGbc.anchor = GridBagConstraints.WEST;
        labelGbc.insets = new Insets(4, 4, 4, 8);

        GridBagConstraints fieldGbc = new GridBagConstraints();
        fieldGbc.gridx = 1;
        fieldGbc.weightx = 1.0;
        fieldGbc.fill = GridBagConstraints.HORIZONTAL;
        fieldGbc.insets = new Insets(4, 0, 4, 4);

        String[] labels = {"Host", "Port", "DBName", "User", "Password"};
        JTextField[] fields = {host, port, db, user, password};
        for (int row = 0; row < labels.length; row++) {
            labelGbc.gridy = row;
            fieldGbc.gridy = row;
            panel.add(new JLabel(labels[row]), labelGbc);
            panel.add(fields[row], fieldGbc);
        }

        GridBagConstraints sslGbc = new GridBagConstraints();
        sslGbc.gridx = 1;
        sslGbc.gridy = labels.length;
        sslGbc.anchor = GridBagConstraints.WEST;
        sslGbc.insets = new Insets(0, 0, 4, 4);
        panel.add(sslCheckBox, sslGbc);

        return panel;
    }

    private JPanel buildMiscPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createTitledBorder("比对范围"));

        GridBagConstraints labelGbc = new GridBagConstraints();
        labelGbc.gridx = 0;
        labelGbc.anchor = GridBagConstraints.WEST;
        labelGbc.insets = new Insets(4, 4, 4, 8);

        GridBagConstraints fieldGbc = new GridBagConstraints();
        fieldGbc.gridx = 1;
        fieldGbc.weightx = 1.0;
        fieldGbc.fill = GridBagConstraints.HORIZONTAL;
        fieldGbc.insets = new Insets(4, 0, 4, 4);

        labelGbc.gridy = 0;
        fieldGbc.gridy = 0;
        panel.add(new JLabel("实例名(逗号分隔,留空=全部)"), labelGbc);
        panel.add(schemaNamesField, fieldGbc);

        JButton queryInstancesButton = new JButton("查询实例...");
        queryInstancesButton.addActionListener(e -> queryAndPickSchemas(queryInstancesButton));
        GridBagConstraints queryGbc = new GridBagConstraints();
        queryGbc.gridx = 2;
        queryGbc.gridy = 0;
        queryGbc.insets = new Insets(4, 4, 4, 4);
        panel.add(queryInstancesButton, queryGbc);

        JButton browseButton = new JButton("浏览...");
        browseButton.addActionListener(e -> {
            JFileChooser chooser = new JFileChooser();
            chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            int choice = chooser.showOpenDialog(this);
            if (choice == JFileChooser.APPROVE_OPTION) {
                outputDirField.setText(chooser.getSelectedFile().getAbsolutePath());
            }
        });

        labelGbc.gridy = 1;
        fieldGbc.gridy = 1;
        GridBagConstraints browseGbc = new GridBagConstraints();
        browseGbc.gridx = 2;
        browseGbc.gridy = 1;
        browseGbc.insets = new Insets(4, 4, 4, 4);
        panel.add(new JLabel("输出目录"), labelGbc);
        panel.add(outputDirField, fieldGbc);
        panel.add(browseButton, browseGbc);
        return panel;
    }

    /** 用当前填的 A 库连接信息去查它有哪些实例（schema），查完弹出多选框 */
    private void queryAndPickSchemas(JButton triggerButton) {
        SchemaDiffService.DbConfig aConfig =
                readConfig(aHostField, aPortField, aDbField, aUserField, aPasswordField, aSslCheckBox);
        if (aConfig.host.isEmpty() || aConfig.dbName.isEmpty()) {
            JOptionPane.showMessageDialog(this, "请先填好 A 库的 Host / DBName / User / Password",
                    "提示", JOptionPane.WARNING_MESSAGE);
            return;
        }

        triggerButton.setEnabled(false);
        SwingWorker<List<String>, Void> worker = new SwingWorker<List<String>, Void>() {
            @Override
            protected List<String> doInBackground() throws Exception {
                return new SchemaDiffService().fetchSchemaNames(aConfig);
            }

            @Override
            protected void done() {
                triggerButton.setEnabled(true);
                try {
                    openSchemaSelectionDialog(get());
                } catch (Exception ex) {
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    JOptionPane.showMessageDialog(PgSchemaDiffApp.this,
                            "查询 A 库实例失败: " + cause, "出错", JOptionPane.ERROR_MESSAGE);
                }
            }
        };
        worker.execute();
    }

    /** 弹一个可多选的实例列表，选好点确定就写回"实例名"文本框（仍然可以手动改） */
    private void openSchemaSelectionDialog(List<String> schemas) {
        if (schemas.isEmpty()) {
            JOptionPane.showMessageDialog(this, "A 库没有查到非系统 schema", "提示", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        JList<String> list = new JList<>(schemas.toArray(new String[0]));
        list.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);

        Set<String> current = Arrays.stream(schemaNamesField.getText().split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
        int[] preselected = java.util.stream.IntStream.range(0, schemas.size())
                .filter(i -> current.contains(schemas.get(i)))
                .toArray();
        list.setSelectedIndices(preselected);

        JScrollPane listScroll = new JScrollPane(list);
        listScroll.setPreferredSize(new Dimension(280, 300));

        int result = JOptionPane.showConfirmDialog(this, listScroll,
                "选择实例（Ctrl/Shift 可多选，留空=全部）", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (result == JOptionPane.OK_OPTION) {
            schemaNamesField.setText(String.join(",", list.getSelectedValuesList()));
        }
    }

    private void runDiff() {
        runButton.setEnabled(false);
        logArea.setText("");
        savePrefs();

        SchemaDiffService.DbConfig aConfig =
                readConfig(aHostField, aPortField, aDbField, aUserField, aPasswordField, aSslCheckBox);
        SchemaDiffService.DbConfig bConfig =
                readConfig(bHostField, bPortField, bDbField, bUserField, bPasswordField, bSslCheckBox);

        List<String> schemaNames = Arrays.stream(schemaNamesField.getText().split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
        String outputDir = outputDirField.getText().trim();

        SwingWorker<Void, String> worker = new SwingWorker<Void, String>() {
            @Override
            protected Void doInBackground() {
                SchemaDiffService service = new SchemaDiffService();
                try {
                    publish("正在连接 A 库...");
                    Map<String, Map<String, Map<String, ColumnInfo>>> aData =
                            service.fetchColumns(aConfig, schemaNames, this::publish);
                    // comment 查询范围用 aData 里已确认能访问的 schema，不用 schemaNames 原始过滤条件
                    // （可能是空=全部，会扫到没权限的 schema）
                    Map<String, Map<String, String>> tableComments =
                            service.fetchTableComments(aConfig, new ArrayList<>(aData.keySet()), this::publish);
                    publish("正在连接 B 库...");
                    Map<String, Map<String, Map<String, ColumnInfo>>> bData =
                            service.fetchColumns(bConfig, schemaNames, this::publish);

                    SchemaDiffService.DiffResult diffResult = service.diff(aData, bData, this::publish);
                    int generated = service.generateReports(aConfig, aData, diffResult, tableComments, outputDir, this::publish);

                    publish("共比对 " + aData.size() + " 个实例，生成 " + generated + " 份差异文档到 " + outputDir + "/");
                } catch (Exception ex) {
                    StringWriter sw = new StringWriter();
                    ex.printStackTrace(new PrintWriter(sw));
                    publish("出错:\n" + sw);
                }
                return null;
            }

            @Override
            protected void process(List<String> chunks) {
                for (String line : chunks) {
                    logArea.append(line + "\n");
                }
                logArea.setCaretPosition(logArea.getDocument().getLength());
            }

            @Override
            protected void done() {
                runButton.setEnabled(true);
            }
        };
        worker.execute();
    }

    private SchemaDiffService.DbConfig readConfig(JTextField host, JTextField port, JTextField db,
                                                    JTextField user, JPasswordField password, JCheckBox sslCheckBox) {
        SchemaDiffService.DbConfig config = new SchemaDiffService.DbConfig();
        config.host = host.getText().trim();
        config.port = parsePort(port.getText());
        config.dbName = db.getText().trim();
        config.user = user.getText().trim();
        config.password = new String(password.getPassword());
        config.ssl = sslCheckBox.isSelected();
        return config;
    }

    private int parsePort(String text) {
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            return 5432;
        }
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignored) {
                // 用默认外观即可
            }
            new PgSchemaDiffApp().setVisible(true);
        });
    }
}
