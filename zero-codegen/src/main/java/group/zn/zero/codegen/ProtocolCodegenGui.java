package group.zn.zero.codegen;

import group.zn.zero.codegen.model.CodegenLanguage;
import group.zn.zero.codegen.model.CodegenRequest;
import group.zn.zero.codegen.model.JavaArtifactKind;
import group.zn.zero.core.error.ZeroException;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
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
    private final JCheckBox boImplBox = new JCheckBox("生成 BOImp");

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
    private final JTextField javaOutField = new JTextField(DEFAULT_OUTPUT_DIR);

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
    private final JTextField csharpOutField = new JTextField("target/generated-sources/zero-codegen-csharp");

    /**
     * TypeScript 输出目录输入框。
     */
    private final JTextField typescriptOutField = new JTextField("target/generated-sources/zero-codegen-typescript");

    /**
     * GDScript 输出目录输入框。
     */
    private final JTextField gdscriptOutField = new JTextField("target/generated-sources/zero-codegen-gdscript");

    /**
     * C# 命名空间输入框。
     */
    private final JTextField csharpNamespaceField = new JTextField("Group.Zn.Zero.Generated");

    /**
     * TypeScript 命名空间输入框。
     */
    private final JTextField typescriptNamespaceField = new JTextField(DEFAULT_PACKAGE);

    /**
     * GDScript 命名空间输入框。
     */
    private final JTextField gdscriptNamespaceField = new JTextField(DEFAULT_PACKAGE);

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
        SwingUtilities.invokeLater(() -> {
            installLookAndFeel();
            new ProtocolCodegenGui().frame.setVisible(true);
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
        root.add(new JScrollPane(createFormPanel()), BorderLayout.CENTER);
        root.add(createLogPanel(), BorderLayout.SOUTH);
        return root;
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

        constraints.gridx = 2;
        constraints.gridy = 29;
        generateButton.addActionListener(ignored -> runGeneration());
        panel.add(generateButton, constraints);
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

    private void runGeneration() {
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
                new ProtocolCodegenRunner().run(options, message -> publish(message));
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
                    JOptionPane.showMessageDialog(frame, "生成完成", "zero-codegen", JOptionPane.INFORMATION_MESSAGE);
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

    private ProtocolCodegenOptions buildOptions() {
        if (inputPathModel.isEmpty()) {
            throw new IllegalArgumentException("请至少添加一个 .si 文件或目录");
        }
        String output = outputField.getText().trim();
        if (output.isBlank()) {
            throw new IllegalArgumentException("输出目录不能为空");
        }
        String namespace = packageField.getText().trim();
        if (namespace.isBlank()) {
            throw new IllegalArgumentException("包名不能为空");
        }
        List<CodegenLanguage> languages = selectedLanguages();
        if (languages.isEmpty()) {
            throw new IllegalArgumentException("请至少选择一种目标语言");
        }
        List<Path> inputPaths = new ArrayList<>();
        for (int index = 0; index < inputPathModel.size(); index++) {
            inputPaths.add(inputPathModel.get(index));
        }
        String protoId = protoIdField.getText().trim();
        return new ProtocolCodegenOptions(
                inputPaths,
                Path.of(output),
                namespace,
                protoId.isBlank() ? null : Path.of(protoId),
                boImplBox.isSelected(),
                languages,
                outputDirs(),
                namespaces(namespace),
                dtoSuffixes(),
                javaArtifactOutputDirs(),
                javaArtifactPackages());
    }

    private List<CodegenLanguage> selectedLanguages() {
        LinkedHashSet<CodegenLanguage> languages = new LinkedHashSet<>();
        if (javaBox.isSelected()) {
            languages.add(CodegenLanguage.JAVA);
        }
        if (csharpBox.isSelected()) {
            languages.add(CodegenLanguage.CSHARP);
        }
        if (typescriptBox.isSelected()) {
            languages.add(CodegenLanguage.TYPESCRIPT);
        }
        if (gdscriptBox.isSelected()) {
            languages.add(CodegenLanguage.GDSCRIPT);
        }
        return List.copyOf(languages);
    }

    private Map<CodegenLanguage, Path> outputDirs() {
        Map<CodegenLanguage, Path> dirs = new EnumMap<>(CodegenLanguage.class);
        dirs.put(CodegenLanguage.JAVA, Path.of(javaOutField.getText().trim()));
        dirs.put(CodegenLanguage.CSHARP, Path.of(csharpOutField.getText().trim()));
        dirs.put(CodegenLanguage.TYPESCRIPT, Path.of(typescriptOutField.getText().trim()));
        dirs.put(CodegenLanguage.GDSCRIPT, Path.of(gdscriptOutField.getText().trim()));
        return dirs;
    }

    private Map<CodegenLanguage, String> namespaces(final String javaNamespace) {
        Map<CodegenLanguage, String> namespaces = new EnumMap<>(CodegenLanguage.class);
        namespaces.put(CodegenLanguage.JAVA, javaNamespace);
        namespaces.put(CodegenLanguage.CSHARP, csharpNamespaceField.getText().trim());
        namespaces.put(CodegenLanguage.TYPESCRIPT, typescriptNamespaceField.getText().trim());
        namespaces.put(CodegenLanguage.GDSCRIPT, gdscriptNamespaceField.getText().trim());
        return namespaces;
    }

    private Map<CodegenLanguage, String> dtoSuffixes() {
        Map<CodegenLanguage, String> suffixes = new EnumMap<>(CodegenLanguage.class);
        suffixes.put(CodegenLanguage.JAVA, javaDtoSuffixField.getText().trim());
        suffixes.put(CodegenLanguage.CSHARP, csharpDtoSuffixField.getText().trim());
        suffixes.put(CodegenLanguage.TYPESCRIPT, typescriptDtoSuffixField.getText().trim());
        suffixes.put(CodegenLanguage.GDSCRIPT, gdscriptDtoSuffixField.getText().trim());
        return suffixes;
    }

    private Map<JavaArtifactKind, Path> javaArtifactOutputDirs() {
        Map<JavaArtifactKind, Path> dirs = new EnumMap<>(JavaArtifactKind.class);
        putJavaArtifactOutputDir(dirs, JavaArtifactKind.DTO, javaDtoOutField);
        putJavaArtifactOutputDir(dirs, JavaArtifactKind.CODEC, javaCodecOutField);
        putJavaArtifactOutputDir(dirs, JavaArtifactKind.PROTOCOL, javaProtocolOutField);
        putJavaArtifactOutputDir(dirs, JavaArtifactKind.BO, javaBoOutField);
        putJavaArtifactOutputDir(dirs, JavaArtifactKind.BO_IMPL, javaBoImplOutField);
        putJavaArtifactOutputDir(dirs, JavaArtifactKind.DISPATCHER, javaDispatcherOutField);
        return dirs;
    }

    private void putJavaArtifactOutputDir(
            final Map<JavaArtifactKind, Path> dirs,
            final JavaArtifactKind kind,
            final JTextField field) {
        String value = field.getText().trim();
        if (!value.isBlank()) {
            dirs.put(kind, Path.of(value));
        }
    }

    private Map<JavaArtifactKind, String> javaArtifactPackages() {
        Map<JavaArtifactKind, String> packages = new EnumMap<>(JavaArtifactKind.class);
        putJavaArtifactPackage(packages, JavaArtifactKind.DTO, javaDtoPackageField);
        putJavaArtifactPackage(packages, JavaArtifactKind.CODEC, javaCodecPackageField);
        putJavaArtifactPackage(packages, JavaArtifactKind.PROTOCOL, javaProtocolPackageField);
        putJavaArtifactPackage(packages, JavaArtifactKind.BO, javaBoPackageField);
        putJavaArtifactPackage(packages, JavaArtifactKind.BO_IMPL, javaBoImplPackageField);
        putJavaArtifactPackage(packages, JavaArtifactKind.DISPATCHER, javaDispatcherPackageField);
        return packages;
    }

    private void putJavaArtifactPackage(
            final Map<JavaArtifactKind, String> packages,
            final JavaArtifactKind kind,
            final JTextField field) {
        String value = field.getText().trim();
        if (!value.isBlank()) {
            packages.put(kind, value);
        }
    }

    private void setGenerating(final boolean generating) {
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
