package group.zn.zero.codegen;

import group.zn.zero.codegen.model.CodegenLanguage;
import group.zn.zero.codegen.model.CodegenRequest;
import group.zn.zero.core.error.ZeroException;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JTabbedPane;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.UIManager;
import javax.swing.UnsupportedLookAndFeelException;
import javax.swing.WindowConstants;

/**
 * 协议代码生成 Swing 图形工具。
 *
 * @author zn
 */
public final class ProtocolCodegenGui {

    /**
     * GUI 默认 Java 协议包名。
     */
    private static final String DEFAULT_PACKAGE = "group.zn.zero.generated";

    /**
     * GUI 默认生成输出目录。
     */
    private static final String DEFAULT_OUTPUT_DIR = "target/generated-sources/zero-codegen";

    /**
     * 主窗口。
     */
    private final JFrame frame;

    /**
     * 输入协议路径列表模型。
     */
    private final DefaultListModel<Path> inputPathModel = new DefaultListModel<>();

    /**
     * 输入协议路径列表组件。
     */
    private final JList<Path> inputPathList = new JList<>(inputPathModel);

    /**
     * protoId 文件输入框。
     */
    private final JTextField protoIdField = new JTextField();

    /**
     * 输出目录输入框。
     */
    private final JTextField outputField = new JTextField(DEFAULT_OUTPUT_DIR);

    /**
     * Java 包名输入框。
     */
    private final JTextField packageField = new JTextField(DEFAULT_PACKAGE);

    /**
     * 是否生成 BO 默认实现。
     */
    private final JCheckBox boImplBox = new JCheckBox("创建 BOImp（保留已有实现）");

    /**
     * 是否生成 Java。
     */
    private final JCheckBox javaBox = new JCheckBox("Java", true);

    /**
     * 是否生成 C#。
     */
    private final JCheckBox csharpBox = new JCheckBox("C#", false);

    /**
     * 是否生成 TypeScript。
     */
    private final JCheckBox typescriptBox = new JCheckBox("TypeScript", false);

    /**
     * 是否生成 GDScript。
     */
    private final JCheckBox gdscriptBox = new JCheckBox("GDScript", false);

    /**
     * Java 输出目录输入框。
     */
    private final JTextField javaOutField = new JTextField();

    /**
     * Java DTO 生成物输出目录覆盖输入框。
     */
    private final JTextField javaDtoOutField = new JTextField();

    /**
     * Java codec 生成物输出目录覆盖输入框。
     */
    private final JTextField javaCodecOutField = new JTextField();

    /**
     * Java protocol 生成物输出目录覆盖输入框。
     */
    private final JTextField javaProtocolOutField = new JTextField();

    /**
     * Java BO 生成物输出目录覆盖输入框。
     */
    private final JTextField javaBoOutField = new JTextField();

    /**
     * Java BOImp 生成物输出目录覆盖输入框。
     */
    private final JTextField javaBoImplOutField = new JTextField();

    /**
     * Java dispatcher 生成物输出目录覆盖输入框。
     */
    private final JTextField javaDispatcherOutField = new JTextField();

    /**
     * C# 输出目录输入框。
     */
    private final JTextField csharpOutField = new JTextField();

    /**
     * TypeScript 输出目录输入框。
     */
    private final JTextField typescriptOutField = new JTextField();

    /**
     * GDScript 输出目录输入框。
     */
    private final JTextField gdscriptOutField = new JTextField();

    /**
     * C# 命名空间输入框。
     */
    private final JTextField csharpNamespaceField = new JTextField();

    /**
     * TypeScript 命名空间输入框。
     */
    private final JTextField typescriptNamespaceField = new JTextField();

    /**
     * GDScript 命名空间输入框。
     */
    private final JTextField gdscriptNamespaceField = new JTextField();

    /**
     * Java DTO 包名覆盖输入框。
     */
    private final JTextField javaDtoPackageField = new JTextField();

    /**
     * Java codec 包名覆盖输入框。
     */
    private final JTextField javaCodecPackageField = new JTextField();

    /**
     * Java protocol 包名覆盖输入框。
     */
    private final JTextField javaProtocolPackageField = new JTextField();

    /**
     * Java BO 包名覆盖输入框。
     */
    private final JTextField javaBoPackageField = new JTextField();

    /**
     * Java BOImp 包名覆盖输入框。
     */
    private final JTextField javaBoImplPackageField = new JTextField();

    /**
     * Java dispatcher 包名覆盖输入框。
     */
    private final JTextField javaDispatcherPackageField = new JTextField();

    /**
     * Java DTO 后缀输入框。
     */
    private final JTextField javaDtoSuffixField = new JTextField(CodegenRequest.DEFAULT_DTO_SUFFIX);

    /**
     * C# DTO 后缀输入框。
     */
    private final JTextField csharpDtoSuffixField = new JTextField(CodegenRequest.DEFAULT_DTO_SUFFIX);

    /**
     * TypeScript DTO 后缀输入框。
     */
    private final JTextField typescriptDtoSuffixField = new JTextField(CodegenRequest.DEFAULT_DTO_SUFFIX);

    /**
     * GDScript DTO 后缀输入框。
     */
    private final JTextField gdscriptDtoSuffixField = new JTextField(CodegenRequest.DEFAULT_DTO_SUFFIX);

    /**
     * 生成按钮。
     */
    private final JButton generateButton = new JButton("生成");

    /** 只读预览按钮，执行期间禁用。 */
    private final JButton previewButton = new JButton("预览变更");

    /** 项目读取按钮，执行期间禁用。 */
    private final JButton loadButton = new JButton("打开项目");

    /** 项目保存按钮，执行期间禁用。 */
    private final JButton saveButton = new JButton("保存项目");

    /**
     * 运行日志输出区域。
     */
    private final JTextArea logArea = new JTextArea(8, 80);

    private ProtocolCodegenGui() {
        frame = new JFrame("zero-codegen");
        frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        frame.setContentPane(createContent());
        frame.setMinimumSize(new Dimension(860, 560));
        frame.pack();
        frame.setLocationRelativeTo(null);
    }

    /**
     * 打开协议代码生成 GUI。
     *
     * <p>该方法会把窗口创建切换到 Swing 事件线程执行；窗口交互不直接修改协议文件，
     * 只在点击生成时写入显式指定的输出目录。</p>
     */
    public static void showWindow() {
        showWindow(Map.of());
    }

    /** 在 EDT 打开已校验的工程参数；不触发生成或写盘。 */
    static void showWindow(final Map<String, String> settings) {
        Map<String, String> snapshot = Map.copyOf(settings);
        SwingUtilities.invokeLater(() -> {
            installLookAndFeel();
            ProtocolCodegenGui gui = new ProtocolCodegenGui();
            if (snapshot.containsKey("--input")) {
                gui.loadProject(snapshot);
            }
            gui.frame.setVisible(true);
        });
    }

    private static void installLookAndFeel() {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (ClassNotFoundException
                | InstantiationException
                | IllegalAccessException
                | UnsupportedLookAndFeelException ignored) {
            // 保留 Swing 默认外观，不影响生成流程。
        }
    }

    private JPanel createContent() {
        JPanel root = new JPanel(new BorderLayout(12, 12));
        root.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        root.add(createTabs(), BorderLayout.CENTER);
        root.add(createToolbar(), BorderLayout.NORTH);
        root.add(createLogPanel(), BorderLayout.SOUTH);
        return root;
    }

    private JTabbedPane createTabs() {
        JPanel form = createFormPanel();
        GridBagLayout layout = (GridBagLayout) form.getLayout();
        JPanel common = new JPanel(new GridBagLayout());
        JPanel targets = new JPanel(new GridBagLayout());
        JPanel advanced = new JPanel(new GridBagLayout());
        for (Component component : form.getComponents()) {
            GridBagConstraints placement = layout.getConstraints(component);
            JPanel target = placement.gridy <= 5 ? common : placement.gridy < 17 ? targets : advanced;
            target.add(component, placement);
        }
        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("工程", new JScrollPane(common));
        tabs.addTab("语言设置", new JScrollPane(targets));
        tabs.addTab("Java 高级布局", new JScrollPane(advanced));
        return tabs;
    }

    private JPanel createToolbar() {
        JPanel bar = new JPanel();
        loadButton.addActionListener(ignored -> chooseProject(false));
        saveButton.addActionListener(ignored -> chooseProject(true));
        previewButton.addActionListener(ignored -> runGeneration("plan"));
        generateButton.addActionListener(ignored -> runGeneration("generate"));
        bar.add(loadButton);
        bar.add(saveButton);
        bar.add(previewButton);
        bar.add(generateButton);
        return bar;
    }

    private JPanel createFormPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createTitledBorder("协议生成"));
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.insets = new Insets(6, 6, 6, 6);
        constraints.fill = GridBagConstraints.HORIZONTAL;

        addLabel(panel, constraints, 0, "输入 .si");
        constraints.gridx = 1;
        constraints.gridy = 0;
        constraints.weightx = 1.0;
        constraints.weighty = 1.0;
        constraints.fill = GridBagConstraints.BOTH;
        JScrollPane inputScrollPane = new JScrollPane(inputPathList);
        inputScrollPane.setPreferredSize(new Dimension(520, 120));
        panel.add(inputScrollPane, constraints);

        constraints.gridx = 2;
        constraints.gridy = 0;
        constraints.weightx = 0.0;
        constraints.weighty = 0.0;
        constraints.fill = GridBagConstraints.VERTICAL;
        panel.add(createInputButtons(), constraints);

        addField(panel, constraints, 1, "protoId", protoIdField, this::chooseProtoIdFile);
        addField(panel, constraints, 2, "输出目录", outputField, () -> chooseDirectory(outputField));
        addLabel(panel, constraints, 3, "包名");
        constraints.gridx = 1;
        constraints.gridy = 3;
        constraints.gridwidth = 2;
        constraints.fill = GridBagConstraints.HORIZONTAL;
        panel.add(packageField, constraints);
        constraints.gridwidth = 1;

        constraints.gridx = 1;
        constraints.gridy = 4;
        panel.add(boImplBox, constraints);

        addLanguagePanel(panel, constraints, 5);
        addField(panel, constraints, 6, "Java 输出", javaOutField, () -> chooseDirectory(javaOutField));
        addField(panel, constraints, 7, "C# 输出", csharpOutField, () -> chooseDirectory(csharpOutField));
        addField(panel, constraints, 8, "TS 输出", typescriptOutField, () -> chooseDirectory(typescriptOutField));
        addField(panel, constraints, 9, "GD 输出", gdscriptOutField, () -> chooseDirectory(gdscriptOutField));
        addField(panel, constraints, 10, "C# 命名空间", csharpNamespaceField, null);
        addField(panel, constraints, 11, "TS 命名空间", typescriptNamespaceField, null);
        addField(panel, constraints, 12, "GD 命名空间", gdscriptNamespaceField, null);
        addField(panel, constraints, 13, "Java DTO 后缀", javaDtoSuffixField, null);
        addField(panel, constraints, 14, "C# DTO 后缀", csharpDtoSuffixField, null);
        addField(panel, constraints, 15, "TS DTO 后缀", typescriptDtoSuffixField, null);
        addField(panel, constraints, 16, "GD DTO 后缀", gdscriptDtoSuffixField, null);

        addField(panel, constraints, 17, "Java DTO 输出", javaDtoOutField, () -> chooseDirectory(javaDtoOutField));
        addField(panel, constraints, 18, "Java Codec 输出", javaCodecOutField, () -> chooseDirectory(javaCodecOutField));
        addField(panel, constraints, 19, "Java Protocol 输出", javaProtocolOutField,
                () -> chooseDirectory(javaProtocolOutField));
        addField(panel, constraints, 20, "Java BO 输出", javaBoOutField, () -> chooseDirectory(javaBoOutField));
        addField(panel, constraints, 21, "Java BOImp 输出", javaBoImplOutField,
                () -> chooseDirectory(javaBoImplOutField));
        addField(panel, constraints, 22, "Java Dispatcher 输出", javaDispatcherOutField,
                () -> chooseDirectory(javaDispatcherOutField));
        addField(panel, constraints, 23, "Java DTO 包名", javaDtoPackageField, null);
        addField(panel, constraints, 24, "Java Codec 包名", javaCodecPackageField, null);
        addField(panel, constraints, 25, "Java Protocol 包名", javaProtocolPackageField, null);
        addField(panel, constraints, 26, "Java BO 包名", javaBoPackageField, null);
        addField(panel, constraints, 27, "Java BOImp 包名", javaBoImplPackageField, null);
        addField(panel, constraints, 28, "Java Dispatcher 包名", javaDispatcherPackageField, null);

        return panel;
    }

    private void addLanguagePanel(
            final JPanel panel,
            final GridBagConstraints constraints,
            final int row) {
        addLabel(panel, constraints, row, "目标语言");
        JPanel languagePanel = new JPanel(new GridBagLayout());
        GridBagConstraints item = new GridBagConstraints();
        item.gridx = 0;
        item.insets = new Insets(0, 2, 0, 8);
        languagePanel.add(javaBox, item);
        item.gridx++;
        languagePanel.add(csharpBox, item);
        item.gridx++;
        languagePanel.add(typescriptBox, item);
        item.gridx++;
        languagePanel.add(gdscriptBox, item);

        constraints.gridx = 1;
        constraints.gridy = row;
        constraints.gridwidth = 2;
        constraints.fill = GridBagConstraints.HORIZONTAL;
        panel.add(languagePanel, constraints);
        constraints.gridwidth = 1;
    }

    private JPanel createInputButtons() {
        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.gridx = 0;
        constraints.fill = GridBagConstraints.HORIZONTAL;
        constraints.insets = new Insets(2, 2, 2, 2);

        JButton addFileButton = new JButton("添加文件");
        addFileButton.addActionListener(ignored -> addInputFiles());
        panel.add(addFileButton, constraints);

        JButton addDirButton = new JButton("添加目录");
        addDirButton.addActionListener(ignored -> addInputDirectory());
        constraints.gridy = 1;
        panel.add(addDirButton, constraints);

        JButton removeButton = new JButton("移除");
        removeButton.addActionListener(ignored -> removeSelectedInputs());
        constraints.gridy = 2;
        panel.add(removeButton, constraints);
        return panel;
    }

    private JPanel createLogPanel() {
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.setBorder(BorderFactory.createTitledBorder("日志"));
        logArea.setEditable(false);
        panel.add(new JScrollPane(logArea), BorderLayout.CENTER);
        return panel;
    }

    private void addLabel(
            final JPanel panel,
            final GridBagConstraints constraints,
            final int row,
            final String text) {
        constraints.gridx = 0;
        constraints.gridy = row;
        constraints.weightx = 0.0;
        constraints.weighty = 0.0;
        constraints.gridwidth = 1;
        constraints.fill = GridBagConstraints.HORIZONTAL;
        panel.add(new JLabel(text), constraints);
    }

    private void addField(
            final JPanel panel,
            final GridBagConstraints constraints,
            final int row,
            final String label,
            final JTextField field,
            final Runnable browseAction) {
        addLabel(panel, constraints, row, label);
        constraints.gridx = 1;
        constraints.gridy = row;
        constraints.weightx = 1.0;
        constraints.fill = GridBagConstraints.HORIZONTAL;
        panel.add(field, constraints);

        if (browseAction != null) {
            JButton browseButton = new JButton("选择");
            browseButton.addActionListener(ignored -> browseAction.run());
            constraints.gridx = 2;
            constraints.weightx = 0.0;
            panel.add(browseButton, constraints);
        }
    }

    private void addInputFiles() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
        chooser.setMultiSelectionEnabled(true);
        if (chooser.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) {
            for (File file : chooser.getSelectedFiles()) {
                addInputPath(file.toPath());
            }
        }
    }

    private void addInputDirectory() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (chooser.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) {
            addInputPath(chooser.getSelectedFile().toPath());
        }
    }

    private void chooseProtoIdFile() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
        if (chooser.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) {
            protoIdField.setText(chooser.getSelectedFile().toPath().toAbsolutePath().normalize().toString());
        }
    }

    private void chooseDirectory(final JTextField target) {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (chooser.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) {
            target.setText(chooser.getSelectedFile().toPath().toAbsolutePath().normalize().toString());
        }
    }

    private void addInputPath(final Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        for (int index = 0; index < inputPathModel.size(); index++) {
            if (normalized.equals(inputPathModel.get(index))) {
                return;
            }
        }
        inputPathModel.addElement(normalized);
    }

    private void removeSelectedInputs() {
        int[] selected = inputPathList.getSelectedIndices();
        for (int index = selected.length - 1; index >= 0; index--) {
            inputPathModel.remove(selected[index]);
        }
    }

    private void runGeneration(final String mode) {
        ProtocolCodegenOptions options;
        try {
            options = buildOptions();
        } catch (RuntimeException ex) {
            showError(ex);
            return;
        }

        logArea.setText("");
        setGenerating(true);
        SwingWorker<Void, String> worker = new SwingWorker<>() {
            @Override
            protected Void doInBackground() {
                CodegenExecution.Report report = CodegenExecution.execute(options, mode);
                publish(mode + ": " + report.messages() + " messages, " + report.protocols() + " protocols");
                report.files().forEach(file -> publish(file.status() + " " + file.path()));
                publish("renderMs=" + report.renderMillis() + " outputMs=" + report.outputMillis());
                return null;
            }

            @Override
            protected void process(final List<String> chunks) {
                for (String chunk : chunks) {
                    appendLog(chunk);
                }
            }

            @Override
            protected void done() {
                setGenerating(false);
                try {
                    get();
                    appendLog("Done.");
                    JOptionPane.showMessageDialog(frame, "plan".equals(mode) ? "预览完成，未写入文件" : "生成完成",
                            "zero-codegen", JOptionPane.INFORMATION_MESSAGE);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    showError(ex);
                } catch (ExecutionException ex) {
                    Throwable failure = ex.getCause() == null ? ex : ex.getCause();
                    appendLog(formatFailure(failure));
                    showError(failure);
                }
            }
        };
        worker.execute();
    }

    private Map<String, JTextField> fields() {
        return Map.ofEntries(
                Map.entry("--protoId", protoIdField), Map.entry("--out", outputField), Map.entry("--pkg", packageField),
                Map.entry("--outJava", javaOutField), Map.entry("--outCs", csharpOutField),
                Map.entry("--outTs", typescriptOutField), Map.entry("--outGd", gdscriptOutField),
                Map.entry("--csNs", csharpNamespaceField), Map.entry("--tsNs", typescriptNamespaceField),
                Map.entry("--gdNs", gdscriptNamespaceField), Map.entry("--javaDtoSuffix", javaDtoSuffixField),
                Map.entry("--csDtoSuffix", csharpDtoSuffixField), Map.entry("--tsDtoSuffix", typescriptDtoSuffixField),
                Map.entry("--gdDtoSuffix", gdscriptDtoSuffixField), Map.entry("--outJavaDto", javaDtoOutField),
                Map.entry("--outJavaCodec", javaCodecOutField), Map.entry("--outJavaProtocol", javaProtocolOutField),
                Map.entry("--outJavaBo", javaBoOutField), Map.entry("--outJavaBoImpl", javaBoImplOutField),
                Map.entry("--outJavaDispatcher", javaDispatcherOutField), Map.entry("--javaDtoPkg", javaDtoPackageField),
                Map.entry("--javaCodecPkg", javaCodecPackageField), Map.entry("--javaProtocolPkg", javaProtocolPackageField),
                Map.entry("--javaBoPkg", javaBoPackageField), Map.entry("--javaBoImplPkg", javaBoImplPackageField),
                Map.entry("--javaDispatcherPkg", javaDispatcherPackageField));
    }

    private Map<String, String> projectValues() {
        Map<String, String> values = new LinkedHashMap<>();
        List<String> inputs = new ArrayList<>();
        for (int i = 0; i < inputPathModel.size(); i++) {
            inputs.add(inputPathModel.get(i).toString());
        }
        values.put("--input", String.join("\n", inputs));
        fields().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            String value = entry.getValue().getText().trim();
            if (!value.isEmpty() || entry.getKey().endsWith("Suffix")) {
                values.put(entry.getKey(), value);
            }
        });
        values.put("--genBoImpl", Boolean.toString(boImplBox.isSelected()));
        values.put("--genJava", Boolean.toString(javaBox.isSelected()));
        values.put("--genCs", Boolean.toString(csharpBox.isSelected()));
        values.put("--genTs", Boolean.toString(typescriptBox.isSelected()));
        values.put("--genGd", Boolean.toString(gdscriptBox.isSelected()));
        return values;
    }

    private void chooseProject(final boolean save) {
        JFileChooser chooser = new JFileChooser();
        chooser.setSelectedFile(new File("codegen.json"));
        int decision = save ? chooser.showSaveDialog(frame) : chooser.showOpenDialog(frame);
        if (decision != JFileChooser.APPROVE_OPTION) {
            return;
        }
        Path path = chooser.getSelectedFile().toPath();
        try {
            if (save) {
                Map<String, String> values = projectValues();
                ProtocolCodegenCli.toCodegenOptions(values);
                CodegenProjectConfig.write(path, values);
            } else {
                loadProject(CodegenProjectConfig.read(path));
            }
            appendLog((save ? "Saved " : "Loaded ") + path);
        } catch (RuntimeException ex) {
            showError(ex);
        }
    }

    private void loadProject(final Map<String, String> values) {
        ProtocolCodegenOptions options = ProtocolCodegenCli.toCodegenOptions(values);
        fields().forEach((key, field) -> field.setText(values.getOrDefault(key,
                key.endsWith("Suffix") ? values.getOrDefault("--dtoSuffix", CodegenRequest.DEFAULT_DTO_SUFFIX) : "")));
        outputField.setText(options.outputDir().toString());
        packageField.setText(options.namespace());
        inputPathModel.clear();
        options.inputPaths().forEach(this::addInputPath);
        boImplBox.setSelected(options.generateBoImpl());
        javaBox.setSelected(options.languages().contains(CodegenLanguage.JAVA));
        csharpBox.setSelected(options.languages().contains(CodegenLanguage.CSHARP));
        typescriptBox.setSelected(options.languages().contains(CodegenLanguage.TYPESCRIPT));
        gdscriptBox.setSelected(options.languages().contains(CodegenLanguage.GDSCRIPT));
    }

    private ProtocolCodegenOptions buildOptions() {
        return ProtocolCodegenCli.toCodegenOptions(projectValues());
    }

    private void setGenerating(final boolean generating) {
        previewButton.setEnabled(!generating);
        loadButton.setEnabled(!generating);
        saveButton.setEnabled(!generating);
        generateButton.setEnabled(!generating);
        generateButton.setText(generating ? "生成中" : "生成");
    }

    private void appendLog(final String message) {
        logArea.append(message);
        logArea.append(System.lineSeparator());
        logArea.setCaretPosition(logArea.getDocument().getLength());
    }

    private void showError(final Throwable failure) {
        JOptionPane.showMessageDialog(
                frame,
                formatFailure(failure),
                "zero-codegen",
                JOptionPane.ERROR_MESSAGE);
    }

    private String formatFailure(final Throwable failure) {
        if (failure instanceof ZeroException ex) {
            return ex.code() + ": " + ex.message();
        }
        String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getName() : message;
    }
}
