/*******************************************************************************
 * Copyright (c) 2017, 2018 Pivotal, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Pivotal, Inc. - initial API and implementation
 *******************************************************************************/
package org.springframework.tooling.boot.ls;

import org.eclipse.jface.preference.BooleanFieldEditor;
import org.eclipse.jface.preference.FieldEditorPreferencePage;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPreferencePage;
import org.springframework.tooling.ls.eclipse.commons.LanguageServerCommonsActivator;
import org.springframework.tooling.ls.eclipse.commons.preferences.PreferenceConstants;

/**
 * Preference page for Boot-Java LS extension
 * 
 * @author Alex Boyko
 *
 */
public class BootLanguageServerPreferencesPage extends FieldEditorPreferencePage implements IWorkbenchPreferencePage {

	public BootLanguageServerPreferencesPage() {
	}

	@Override
	public void init(IWorkbench workbench) {
		setPreferenceStore(BootLanguageServerPlugin.getDefault().getPreferenceStore());
	}

	@Override
	protected void createFieldEditors() {
		
		Composite parent = getFieldEditorParent();
		
		Label l = new Label(parent, SWT.NONE);
		l.setFont(parent.getFont());
		l.setText("Settings for Spring Boot Live Beans data:");
		GridData gd = new GridData(GridData.FILL_HORIZONTAL);
		gd.horizontalSpan = 2;
		gd.grabExcessHorizontalSpace = false;
		l.setLayoutData(gd);

		BooleanFieldEditor liveHintsPrefEditor = new BooleanFieldEditor(Constants.PREF_BOOT_HINTS, "Live Boot Hint Decorators", parent);
		addField(liveHintsPrefEditor);
		
		final IPreferenceStore commonsLsPrefs = LanguageServerCommonsActivator.getInstance().getPreferenceStore();
		addField(new BooleanFieldEditor(PreferenceConstants.HIGHLIGHT_CODELENS_PREFS, "Highlights CodeLens (Experimental)", parent) {

			@Override
			public IPreferenceStore getPreferenceStore() {
				return commonsLsPrefs;
			}
			
		});
		
		BooleanFieldEditor liveChangeDetectionPrefEditor = new BooleanFieldEditor(Constants.PREF_CHANGE_DETECTION, "Live Boot Change Detection", parent);
		addField(liveChangeDetectionPrefEditor);
		
		l = new Label(parent, SWT.NONE);
		gd = new GridData(GridData.FILL_HORIZONTAL);
		gd.horizontalSpan = 2;
		l.setLayoutData(gd);

	}

}
