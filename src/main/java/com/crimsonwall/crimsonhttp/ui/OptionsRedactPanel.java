/*
 * Crimson HTTP - HTTP Request/Response Viewer for ZAP.
 *
 * Renico Koen / Crimson Wall / 2026
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.crimsonwall.crimsonhttp.ui;

import com.crimsonwall.crimsonhttp.ExtensionCrimsonHttp;
import com.crimsonwall.crimsonhttp.redact.RedactConfig;
import com.crimsonwall.crimsonhttp.redact.RedactEntry;
import java.awt.Component;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import javax.swing.AbstractCellEditor;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.TableCellEditor;
import javax.swing.table.TableCellRenderer;
import org.parosproxy.paros.Constant;
import org.parosproxy.paros.view.AbstractParamPanel;

/**
 * Options panel registered under Tools → Options → Crimson HTTP.
 *
 * <p>Allows the user to configure:
 * <ul>
 *   <li>Enable/disable display redaction
 *   <li>Replacement text for redacted content
 *   <li>Screenshot-specific settings (redact, light mode, optimize space)
 *   <li>The list of regex redaction rules via an editable table
 * </ul>
 */
public class OptionsRedactPanel extends AbstractParamPanel {

    private static final long serialVersionUID = 1L;
    private static final int MAX_REGEX_RULES = 200;

    private final ExtensionCrimsonHttp extension;

    private JCheckBox enableCheckBox;
    private JTextField replacementField;
    private JCheckBox screenshotCheckBox;
    private JCheckBox lightModeCheckBox;
    private JCheckBox optimizeSpaceCheckBox;
    private RedactTableModel tableModel;
    private JTable redactTable;

    /**
     * Constructs the options panel.
     *
     * @param extension the parent extension used to read/write the redaction config
     */
    public OptionsRedactPanel(ExtensionCrimsonHttp extension) {
        this.extension = extension;
        setName(Constant.messages.getString("crimsonhttp.options.title"));
        initComponents();
    }

    private void initComponents() {
        setLayout(new GridBagLayout());

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.anchor = GridBagConstraints.NORTHWEST;

        JPanel generalPanel = new JPanel(new GridBagLayout());
        generalPanel.setBorder(
                BorderFactory.createTitledBorder(
                        Constant.messages.getString("crimsonhttp.options.general.title")));

        GridBagConstraints gc = new GridBagConstraints();
        gc.fill = GridBagConstraints.HORIZONTAL;
        gc.anchor = GridBagConstraints.WEST;
        gc.weightx = 1.0;

        int row = 0;

        // Display redaction section
        gc.gridx = 0;
        gc.gridy = row;
        gc.gridwidth = 2;
        gc.insets = new Insets(4, 4, 2, 4);
        JLabel displaySectionLabel =
                new JLabel(Constant.messages.getString("crimsonhttp.options.display"));
        displaySectionLabel.setFont(displaySectionLabel.getFont().deriveFont(java.awt.Font.BOLD));
        displaySectionLabel.setForeground(displaySectionLabel.getForeground().darker());
        generalPanel.add(displaySectionLabel, gc);

        gc.gridy = ++row;
        gc.insets = new Insets(0, 4, 4, 4);
        enableCheckBox = new JCheckBox(Constant.messages.getString("crimsonhttp.options.enable"));
        enableCheckBox.setToolTipText(
                Constant.messages.getString("crimsonhttp.options.enable.tooltip"));
        generalPanel.add(enableCheckBox, gc);

        // Screenshot settings section
        gc.gridy = ++row;
        gc.insets = new Insets(8, 4, 2, 4);
        JLabel screenshotSectionLabel =
                new JLabel(Constant.messages.getString("crimsonhttp.options.screenshot.section"));
        screenshotSectionLabel.setFont(
                screenshotSectionLabel.getFont().deriveFont(java.awt.Font.BOLD));
        screenshotSectionLabel.setForeground(screenshotSectionLabel.getForeground().darker());
        generalPanel.add(screenshotSectionLabel, gc);

        gc.gridy = ++row;
        gc.insets = new Insets(0, 4, 4, 4);
        screenshotCheckBox =
                new JCheckBox(Constant.messages.getString("crimsonhttp.options.screenshot"));
        screenshotCheckBox.setToolTipText(
                Constant.messages.getString("crimsonhttp.options.screenshot.tooltip"));
        generalPanel.add(screenshotCheckBox, gc);

        gc.gridy = ++row;
        gc.insets = new Insets(0, 4, 4, 4);
        lightModeCheckBox =
                new JCheckBox(Constant.messages.getString("crimsonhttp.options.lightmode"));
        lightModeCheckBox.setToolTipText(
                Constant.messages.getString("crimsonhttp.options.lightmode.tooltip"));
        generalPanel.add(lightModeCheckBox, gc);

        gc.gridy = ++row;
        gc.insets = new Insets(0, 4, 4, 4);
        optimizeSpaceCheckBox =
                new JCheckBox(Constant.messages.getString("crimsonhttp.options.optimizespace"));
        optimizeSpaceCheckBox.setToolTipText(
                Constant.messages.getString("crimsonhttp.options.optimizespace.tooltip"));
        generalPanel.add(optimizeSpaceCheckBox, gc);

        // Replacement text field
        gc.gridy = ++row;
        gc.gridwidth = 1;
        gc.gridx = 0;
        gc.weightx = 0.0;
        gc.insets = new Insets(8, 4, 4, 4);
        JLabel replacementLabel =
                new JLabel(Constant.messages.getString("crimsonhttp.options.replacement"));
        generalPanel.add(replacementLabel, gc);

        gc.gridx = 1;
        gc.weightx = 1.0;
        gc.insets = new Insets(8, 4, 4, 4);
        replacementField = new JTextField("[redacted]", 20);
        replacementField.setToolTipText(
                Constant.messages.getString("crimsonhttp.options.replacement.tooltip"));
        replacementLabel.setLabelFor(replacementField);
        generalPanel.add(replacementField, gc);

        add(generalPanel, gbc);

        gbc.gridy = 1;
        gbc.weighty = 1.0;
        gbc.fill = GridBagConstraints.BOTH;
        gbc.insets = new Insets(8, 0, 0, 0);
        add(createTablePanel(), gbc);
    }

    private JPanel createTablePanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(
                BorderFactory.createTitledBorder(
                        Constant.messages.getString("crimsonhttp.options.regex.title")));

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.weightx = 1.0;
        gbc.weighty = 1.0;
        gbc.fill = GridBagConstraints.BOTH;
        gbc.insets = new Insets(4, 4, 4, 4);

        tableModel = new RedactTableModel();
        redactTable =
                new JTable(tableModel) {
                    private static final long serialVersionUID = 1L;

                    @Override
                    public Class<?> getColumnClass(int column) {
                        switch (column) {
                            case 0:
                                return String.class;
                            case 1:
                                return String.class;
                            case 2:
                                return Boolean.class;
                            default:
                                return Object.class;
                        }
                    }
                };

        redactTable.setSelectionMode(javax.swing.ListSelectionModel.SINGLE_SELECTION);
        redactTable.setDefaultEditor(String.class, new RegexCellEditor());
        redactTable.setDefaultRenderer(Boolean.class, new CheckboxRenderer());
        redactTable.setDefaultEditor(Boolean.class, new CheckboxEditor());
        redactTable.getColumnModel().getColumn(0).setPreferredWidth(120);
        redactTable.getColumnModel().getColumn(1).setPreferredWidth(350);
        redactTable.getColumnModel().getColumn(2).setPreferredWidth(60);

        // Click on the Enabled column header to toggle all rules on/off
        redactTable
                .getTableHeader()
                .addMouseListener(
                        new java.awt.event.MouseAdapter() {
                            @Override
                            public void mouseClicked(java.awt.event.MouseEvent e) {
                                int col = redactTable.columnAtPoint(e.getPoint());
                                if (col != 2) {
                                    return;
                                }
                                boolean newValue = false;
                                for (int i = 0; i < tableModel.getRowCount(); i++) {
                                    if (!((Boolean) tableModel.getValueAt(i, col))) {
                                        newValue = true;
                                        break;
                                    }
                                }
                                for (int i = 0; i < tableModel.getRowCount(); i++) {
                                    tableModel.setValueAt(Boolean.valueOf(newValue), i, col);
                                }
                            }
                        });

        panel.add(new JScrollPane(redactTable), gbc);

        gbc.gridy = 1;
        gbc.weighty = 0.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(4, 4, 4, 4);

        JPanel toolbar = new JPanel();
        toolbar.setLayout(new BoxLayout(toolbar, BoxLayout.X_AXIS));

        JButton addButton =
                new JButton(Constant.messages.getString("crimsonhttp.options.regex.button.add"));
        addButton.setToolTipText(
                Constant.messages.getString("crimsonhttp.options.regex.button.add.tooltip"));
        addButton.addActionListener(
                e -> {
                    String[] result = showAddDialog();
                    if (result != null) {
                        tableModel.addEntry(new RedactEntry(result[0], result[1], true));
                        updateConfigFromTable();
                    }
                });
        toolbar.add(addButton);
        toolbar.add(Box.createHorizontalStrut(4));

        JButton removeButton =
                new JButton(
                        Constant.messages.getString("crimsonhttp.options.regex.button.remove"));
        removeButton.setToolTipText(
                Constant.messages.getString("crimsonhttp.options.regex.button.remove.tooltip"));
        removeButton.addActionListener(
                e -> {
                    int row = redactTable.getSelectedRow();
                    if (row >= 0) {
                        tableModel.removeEntry(row);
                        updateConfigFromTable();
                    }
                });
        toolbar.add(removeButton);

        panel.add(toolbar, gbc);
        return panel;
    }

    /**
     * Shows a dialog to collect a rule name and regex pattern.
     *
     * @return a two-element array {@code [name, regex]}, or {@code null} if cancelled or invalid
     */
    private String[] showAddDialog() {
        JTextField nameField = new JTextField(20);
        JTextField regexField = new JTextField(40);

        JPanel dialogPanel = new JPanel(new GridBagLayout());
        GridBagConstraints dc = new GridBagConstraints();
        dc.fill = GridBagConstraints.HORIZONTAL;
        dc.anchor = GridBagConstraints.WEST;

        dc.gridx = 0;
        dc.gridy = 0;
        dc.insets = new Insets(4, 4, 4, 4);
        JLabel nameLabel =
                new JLabel(Constant.messages.getString("crimsonhttp.options.regex.dialog.name"));
        nameLabel.setLabelFor(nameField);
        dialogPanel.add(nameLabel, dc);

        dc.gridx = 1;
        dc.weightx = 1.0;
        dialogPanel.add(nameField, dc);

        dc.gridx = 0;
        dc.gridy = 1;
        dc.weightx = 0.0;
        JLabel regexLabel =
                new JLabel(Constant.messages.getString("crimsonhttp.options.regex.dialog.regex"));
        regexLabel.setLabelFor(regexField);
        dialogPanel.add(regexLabel, dc);

        dc.gridx = 1;
        dc.weightx = 1.0;
        dialogPanel.add(regexField, dc);

        int result =
                JOptionPane.showConfirmDialog(
                        (Component) this,
                        dialogPanel,
                        Constant.messages.getString(
                                "crimsonhttp.options.regex.dialog.title"),
                        JOptionPane.OK_CANCEL_OPTION,
                        JOptionPane.PLAIN_MESSAGE);

        if (result == JOptionPane.OK_OPTION) {
            String name = nameField.getText().trim();
            String regex = regexField.getText().trim();
            if (!name.isEmpty() && !regex.isEmpty()) {
                try {
                    Pattern.compile(regex);
                } catch (PatternSyntaxException ex) {
                    JOptionPane.showMessageDialog(
                            (Component) this,
                            Constant.messages.getString(
                                            "crimsonhttp.options.regex.dialog.invalid")
                                    + " "
                                    + ex.getMessage(),
                            Constant.messages.getString(
                                    "crimsonhttp.options.regex.dialog.error"),
                            JOptionPane.ERROR_MESSAGE);
                    return null;
                }
                return new String[] {name, regex};
            }
        }
        return null;
    }

    private void updateConfigFromTable() {
        RedactConfig config = extension.getRedactConfig();
        config.setEntries(new ArrayList<>(tableModel.getEntries()));
        SwingUtilities.invokeLater(() -> extension.refreshCurrentMessage());
    }

    @Override
    public void initParam(Object obj) {
        RedactConfig config = extension.getRedactConfig();
        enableCheckBox.setSelected(config.isEnabled());
        replacementField.setText(config.getReplacementText());
        screenshotCheckBox.setSelected(config.isRedactScreenshots());
        lightModeCheckBox.setSelected(config.isLightModeScreenshots());
        optimizeSpaceCheckBox.setSelected(config.isOptimizeScreenshotSpace());
        tableModel.setEntries(config.getEntries());
    }

    @Override
    public void saveParam(Object obj) throws Exception {
        RedactConfig config = extension.getRedactConfig();
        config.setEnabled(enableCheckBox.isSelected());
        String replacementText = replacementField.getText().trim();
        config.setReplacementText(replacementText.isEmpty() ? "[redacted]" : replacementText);
        config.setRedactScreenshots(screenshotCheckBox.isSelected());
        config.setLightModeScreenshots(lightModeCheckBox.isSelected());
        config.setOptimizeScreenshotSpace(optimizeSpaceCheckBox.isSelected());
        config.setEntries(new ArrayList<>(tableModel.getEntries()));
        config.save();
        config.saveEntries();
        SwingUtilities.invokeLater(() -> extension.refreshCurrentMessage());
    }

    @Override
    public String getHelpIndex() {
        return "crimsonhttp.options";
    }

    // -------------------------------------------------------------------------
    // Inner classes
    // -------------------------------------------------------------------------

    private class RedactTableModel extends AbstractTableModel {

        private static final long serialVersionUID = 1L;

        private final String[] columnNames = {
            Constant.messages.getString("crimsonhttp.options.regex.col.name"),
            Constant.messages.getString("crimsonhttp.options.regex.col.regex"),
            Constant.messages.getString("crimsonhttp.options.regex.col.enabled")
        };

        private List<RedactEntry> entries = new ArrayList<>();

        @Override
        public int getRowCount() {
            return entries.size();
        }

        @Override
        public int getColumnCount() {
            return columnNames.length;
        }

        @Override
        public String getColumnName(int column) {
            return columnNames[column];
        }

        @Override
        public boolean isCellEditable(int rowIndex, int columnIndex) {
            return true;
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            RedactEntry entry = entries.get(rowIndex);
            switch (columnIndex) {
                case 0:
                    return entry.getName();
                case 1:
                    return entry.getPattern();
                case 2:
                    return Boolean.valueOf(entry.isEnabled());
                default:
                    return null;
            }
        }

        @Override
        public void setValueAt(Object value, int rowIndex, int columnIndex) {
            RedactEntry entry = entries.get(rowIndex);
            switch (columnIndex) {
                case 0:
                    entry.setName((String) value);
                    break;
                case 1:
                    String newPattern = (String) value;
                    if (newPattern != null && !newPattern.isEmpty()) {
                        try {
                            Pattern.compile(newPattern);
                        } catch (PatternSyntaxException ex) {
                            fireTableCellUpdated(rowIndex, columnIndex);
                            JOptionPane.showMessageDialog(
                                    (Component) OptionsRedactPanel.this,
                                    Constant.messages.getString(
                                                    "crimsonhttp.options.regex.dialog.invalid")
                                            + " "
                                            + ex.getMessage(),
                                    Constant.messages.getString(
                                            "crimsonhttp.options.regex.dialog.error"),
                                    JOptionPane.ERROR_MESSAGE);
                            return;
                        }
                    }
                    entry.setPattern(newPattern);
                    break;
                case 2:
                    entry.setEnabled(((Boolean) value));
                    break;
                default:
                    break;
            }
            fireTableCellUpdated(rowIndex, columnIndex);
            updateConfigFromTable();
        }

        public void addEntry(RedactEntry entry) {
            if (entries.size() >= MAX_REGEX_RULES) {
                JOptionPane.showMessageDialog(
                        (Component) OptionsRedactPanel.this,
                        Constant.messages.getString("crimsonhttp.options.regex.limit")
                                + " (" + MAX_REGEX_RULES + ")",
                        Constant.messages.getString("crimsonhttp.options.regex.dialog.error"),
                        JOptionPane.WARNING_MESSAGE);
                return;
            }
            entries.add(entry);
            fireTableRowsInserted(entries.size() - 1, entries.size() - 1);
        }

        public void removeEntry(int row) {
            entries.remove(row);
            fireTableRowsDeleted(row, row);
        }

        public List<RedactEntry> getEntries() {
            return entries;
        }

        public void setEntries(List<RedactEntry> entries) {
            this.entries = new ArrayList<>(entries);
            fireTableDataChanged();
        }
    }

    private static class RegexCellEditor extends AbstractCellEditor implements TableCellEditor {

        private final JTextField field = new JTextField();

        @Override
        public Component getTableCellEditorComponent(
                JTable table, Object value, boolean isSelected, int row, int column) {
            field.setText((value != null) ? value.toString() : "");
            return field;
        }

        @Override
        public Object getCellEditorValue() {
            return field.getText();
        }
    }

    private static class CheckboxRenderer extends JCheckBox implements TableCellRenderer {

        CheckboxRenderer() {
            setHorizontalAlignment(CENTER);
        }

        @Override
        public Component getTableCellRendererComponent(
                JTable table,
                Object value,
                boolean isSelected,
                boolean hasFocus,
                int row,
                int column) {
            setSelected(value != null && (Boolean) value);
            setBackground(isSelected ? table.getSelectionBackground() : table.getBackground());
            return this;
        }
    }

    private static class CheckboxEditor extends AbstractCellEditor implements TableCellEditor {

        private final JCheckBox checkbox = new JCheckBox();

        CheckboxEditor() {
            checkbox.setHorizontalAlignment(JCheckBox.CENTER);
        }

        @Override
        public Component getTableCellEditorComponent(
                JTable table, Object value, boolean isSelected, int row, int column) {
            checkbox.setSelected(value != null && (Boolean) value);
            return checkbox;
        }

        @Override
        public Object getCellEditorValue() {
            return Boolean.valueOf(checkbox.isSelected());
        }
    }
}
