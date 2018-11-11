/*
 * Copyright 2011-2014 the original author or authors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.dimecoinnetwork.wallet.ui;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.Writer;
import java.text.DateFormat;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.TimeZone;

import javax.annotation.Nonnull;


//import com.google.bitcoin.core.*;
//import org.slf4j.Logger;
//import org.slf4j.LoggerFactory;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.DialogInterface;
import android.content.DialogInterface.OnCancelListener;
import android.content.DialogInterface.OnClickListener;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.net.Uri;
import android.nfc.NdefMessage;
import android.nfc.NfcAdapter;
import android.os.Bundle;
import android.os.Environment;
import android.support.annotation.NonNull;

import android.text.format.DateUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;

import android.view.Menu;
import android.view.MenuItem;

import com.google.bitcoin.core.ECKey;
import com.google.bitcoin.core.Transaction;
import com.google.bitcoin.core.Wallet;

import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v4.content.FileProvider;
import android.support.v7.app.ActionBar;


import com.dimecoinnetwork.wallet.Configuration;
import com.dimecoinnetwork.wallet.Constants;
import com.dimecoinnetwork.wallet.PaymentIntent;
import com.dimecoinnetwork.wallet.WalletApplication;
import com.dimecoinnetwork.wallet.ui.InputParser.BinaryInputParser;
import com.dimecoinnetwork.wallet.ui.InputParser.StringInputParser;
import com.dimecoinnetwork.wallet.util.Crypto;
import com.dimecoinnetwork.wallet.util.Iso8601Format;
import com.dimecoinnetwork.wallet.util.Nfc;
import com.dimecoinnetwork.wallet.util.WalletUtils;

import com.google.bitcoin.core.CoinDefinition;
import com.dimecoinnetwork.wallet.R;


/**
 * @author Andreas Schildbach
 */
public final class WalletActivity extends AbstractOnDemandServiceActivity implements ActivityCompat.OnRequestPermissionsResultCallback
{
	private static final int DIALOG_IMPORT_KEYS = 0;
	private static final int DIALOG_EXPORT_KEYS = 1;
	private static final int DIALOG_CHANGELOG = 2;

	private WalletApplication application;
	private Configuration config;
	private Wallet wallet;

	private static final int REQUEST_CODE_SCAN = 0;
	private static final int REQUEST_CAMERA_PERMISSION = 1;
	private static final int REQUEST_READ_PERMISSION = 2;
	private static final int REQUEST_WRITE_PERMISSION = 3;

	private static final int DEFAULT_PRECISION_CHANGE_VERSION_CODE = 152;

	@Override
	protected void onCreate(final Bundle savedInstanceState)
	{
		super.onCreate(savedInstanceState);

		application = getWalletApplication();
		config = application.getConfiguration();
		wallet = application.getWallet();

		setContentView(R.layout.wallet_content);

		ActionBar actionBar = getSupportActionBar();
		actionBar.setDisplayShowHomeEnabled(true);
		actionBar.setIcon(R.mipmap.ic_launcher);

		config.touchLastUsed();

		handleIntent(getIntent());
	}

	@Override
	protected void onResume()
	{
		super.onResume();

		getWalletApplication().startBlockchainService(true);

		checkLowStorageAlert();
	}

	@Override
	protected void onNewIntent(final Intent intent)
	{
		handleIntent(intent);
	}

	private void handleIntent(@Nonnull final Intent intent)
	{
		final String action = intent.getAction();

		if (NfcAdapter.ACTION_NDEF_DISCOVERED.equals(action))
		{
			final String inputType = intent.getType();
			final NdefMessage ndefMessage = (NdefMessage) intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES)[0];
			final byte[] input = Nfc.extractMimePayload(Constants.MIMETYPE_TRANSACTION, ndefMessage);

			new BinaryInputParser(inputType, input)
			{
				@Override
				protected void handlePaymentIntent(final PaymentIntent paymentIntent)
				{
					cannotClassify(inputType);
				}

				@Override
				protected void handleDirectTransaction(final Transaction transaction)
				{
					processDirectTransaction(transaction);
				}

				@Override
				protected void error(final int messageResId, final Object... messageArgs)
				{
					dialog(WalletActivity.this, null, 0, messageResId, messageArgs);
				}
			}.parse();
		}
	}

	public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
		if (requestCode == REQUEST_CAMERA_PERMISSION) {
			if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
				handleScan();
			}
		}
		if (requestCode == REQUEST_READ_PERMISSION) {
			if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
				showDialog(DIALOG_IMPORT_KEYS);
			}
		}
		if (requestCode == REQUEST_WRITE_PERMISSION) {
			if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
				handleExportKeys();
			}
		}
	}

	@Override
	public void onActivityResult(final int requestCode, final int resultCode, final Intent intent)
	{
		if (requestCode == REQUEST_CODE_SCAN && resultCode == Activity.RESULT_OK)
		{
			final String input = intent.getStringExtra(ScanActivity.INTENT_EXTRA_RESULT);

			new StringInputParser(input)
			{
				@Override
				protected void handlePaymentIntent(final PaymentIntent paymentIntent)
				{
					SendCoinsActivity.start(WalletActivity.this, paymentIntent);
				}

				@Override
				protected void handleDirectTransaction(final Transaction tx)
				{
					processDirectTransaction(tx);
				}

				@Override
				protected void error(final int messageResId, final Object... messageArgs)
				{
					dialog(WalletActivity.this, null, R.string.button_scan, messageResId, messageArgs);
				}
			}.parse();
		}
	}

	@Override
	public boolean onCreateOptionsMenu(final Menu menu)
	{
		super.onCreateOptionsMenu(menu);

		getMenuInflater().inflate(R.menu.wallet_options, menu);
		return true;
	}

	@Override
	public boolean onPrepareOptionsMenu(final Menu menu)
	{
		super.onPrepareOptionsMenu(menu);

		final Resources res = getResources();
		final String externalStorageState = Environment.getExternalStorageState();

		menu.findItem(R.id.wallet_options_exchange_rates).setVisible(res.getBoolean(R.bool.show_exchange_rates_option));
		menu.findItem(R.id.wallet_options_import_keys).setEnabled(
				Environment.MEDIA_MOUNTED.equals(externalStorageState) || Environment.MEDIA_MOUNTED_READ_ONLY.equals(externalStorageState));
		menu.findItem(R.id.wallet_options_export_keys).setEnabled(Environment.MEDIA_MOUNTED.equals(externalStorageState));

		return true;
	}

	@Override
	public boolean onOptionsItemSelected(final MenuItem item)
	{
		switch (item.getItemId())
		{
			case R.id.wallet_options_address_book:
				AddressBookActivity.start(this, true);
				return true;

			case R.id.wallet_options_exchange_rates:
				startActivity(new Intent(this, ExchangeRatesActivity.class));
				return true;

			case R.id.wallet_options_network_monitor:
				startActivity(new Intent(this, NetworkMonitorActivity.class));
				return true;

			case R.id.wallet_options_import_keys:
				if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
					ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, REQUEST_READ_PERMISSION);
				} else {
					showDialog(DIALOG_IMPORT_KEYS);
				}
				return true;

			case R.id.wallet_options_export_keys:
				handleExportKeys();
				return true;

			case R.id.wallet_options_preferences:
				startActivity(new Intent(this, PreferencesActivity.class));
				return true;

			case R.id.wallet_options_about:
				startActivity(new Intent(this, AboutActivity.class));
				return true;

			case R.id.wallet_options_safety:
				HelpDialogFragment.page(getSupportFragmentManager(), R.string.help_safety);
				return true;

			case R.id.wallet_options_help:
				HelpDialogFragment.page(getSupportFragmentManager(), R.string.help_wallet);
				return true;
		}

		return super.onOptionsItemSelected(item);
	}

	public void handleRequestCoins()
	{
		startActivity(new Intent(this, RequestCoinsActivity.class));
	}

	public void handleSendCoins()
	{
		startActivity(new Intent(this, SendCoinsActivity.class));
	}

	public void handleScan()
	{
		if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
			ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA_PERMISSION);
		} else {
			startActivityForResult(new Intent(this, ScanActivity.class), REQUEST_CODE_SCAN);
		}
	}

	public void handleExportKeys()
	{
		if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
			ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQUEST_WRITE_PERMISSION);
		} else {
			showDialog(DIALOG_EXPORT_KEYS);
			config.disarmBackupReminder();
		}
	}

	@Override
	protected Dialog onCreateDialog(final int id)
	{
		if (id == DIALOG_IMPORT_KEYS)
			return createImportKeysDialog();
		else if (id == DIALOG_EXPORT_KEYS)
			return createExportKeysDialog();
		else if (id == DIALOG_CHANGELOG)
			return createChangeLogDialog();
		else
			throw new IllegalArgumentException();
	}

	@Override
	protected void onPrepareDialog(final int id, final Dialog dialog)
	{
		if (id == DIALOG_IMPORT_KEYS)
			prepareImportKeysDialog(dialog);
		else if (id == DIALOG_EXPORT_KEYS)
			prepareExportKeysDialog(dialog);
	}

	private Dialog createImportKeysDialog()
	{
		final View view = getLayoutInflater().inflate(R.layout.import_keys_from_storage_dialog, null);
		final Spinner fileView = (Spinner) view.findViewById(R.id.import_keys_from_storage_file);
		final EditText passwordView = (EditText) view.findViewById(R.id.import_keys_from_storage_password);

		final DialogBuilder dialog = new DialogBuilder(this);
		dialog.setTitle(R.string.import_keys_dialog_title);
		dialog.setView(view);
		dialog.setPositiveButton(R.string.import_keys_dialog_button_import, new OnClickListener()
		{
			@Override
			public void onClick(final DialogInterface dialog, final int which)
			{
				final File file = (File) fileView.getSelectedItem();
				final String password = passwordView.getText().toString().trim();
				passwordView.setText(null); // get rid of it asap

				importPrivateKeys(file, password);
			}
		});
		dialog.setNegativeButton(R.string.button_cancel, new OnClickListener()
		{
			@Override
			public void onClick(final DialogInterface dialog, final int which)
			{
				passwordView.setText(null); // get rid of it asap
			}
		});
		dialog.setOnCancelListener(new OnCancelListener()
		{
			@Override
			public void onCancel(final DialogInterface dialog)
			{
				passwordView.setText(null); // get rid of it asap
			}
		});

		final FileAdapter adapter = new FileAdapter(this)
		{
			@Override
			public View getDropDownView(final int position, View row, final ViewGroup parent)
			{
				final File file = getItem(position);
				final boolean isExternal = Constants.EXTERNAL_WALLET_BACKUP_DIR.equals(file.getParentFile());
				final boolean isEncrypted = Crypto.OPENSSL_FILE_FILTER.accept(file);

				if (row == null)
					row = inflater.inflate(R.layout.wallet_import_keys_file_row, null);

				final TextView filenameView = (TextView) row.findViewById(R.id.wallet_import_keys_file_row_filename);
				filenameView.setText(file.getName());

				final TextView securityView = (TextView) row.findViewById(R.id.wallet_import_keys_file_row_security);
				final String encryptedStr = context.getString(isEncrypted ? R.string.import_keys_dialog_file_security_encrypted
						: R.string.import_keys_dialog_file_security_unencrypted);
				final String storageStr = context.getString(isExternal ? R.string.import_keys_dialog_file_security_external
						: R.string.import_keys_dialog_file_security_internal);
				securityView.setText(encryptedStr + ", " + storageStr);

				final TextView createdView = (TextView) row.findViewById(R.id.wallet_import_keys_file_row_created);
				createdView
						.setText(context.getString(isExternal ? R.string.import_keys_dialog_file_created_manual
								: R.string.import_keys_dialog_file_created_automatic, DateUtils.getRelativeTimeSpanString(context,
								file.lastModified(), true)));

				return row;
			}
		};

		fileView.setAdapter(adapter);

		return dialog.create();
	}

	private void prepareImportKeysDialog(final Dialog dialog)
	{
		final AlertDialog alertDialog = (AlertDialog) dialog;

		final List<File> files = new LinkedList<File>();

		// external storage
		if (Constants.EXTERNAL_WALLET_BACKUP_DIR.exists() && Constants.EXTERNAL_WALLET_BACKUP_DIR.isDirectory())
			for (final File file : Constants.EXTERNAL_WALLET_BACKUP_DIR.listFiles())
				if (WalletUtils.KEYS_FILE_FILTER.accept(file) || Crypto.OPENSSL_FILE_FILTER.accept(file))
					files.add(file);

		// internal storage
		for (final String filename : fileList())
			if (filename.startsWith(Constants.WALLET_KEY_BACKUP_BASE58 + '.'))
				files.add(new File(getFilesDir(), filename));

		// sort
		Collections.sort(files, new Comparator<File>()
		{
			@Override
			public int compare(final File lhs, final File rhs)
			{
				return lhs.getName().compareToIgnoreCase(rhs.getName());
			}
		});

		final Spinner fileView = (Spinner) alertDialog.findViewById(R.id.import_keys_from_storage_file);
		final FileAdapter adapter = (FileAdapter) fileView.getAdapter();
		adapter.setFiles(files);
		fileView.setEnabled(!adapter.isEmpty());

		final EditText passwordView = (EditText) alertDialog.findViewById(R.id.import_keys_from_storage_password);
		passwordView.setText(null);

		final ImportDialogButtonEnablerListener dialogButtonEnabler = new ImportDialogButtonEnablerListener(passwordView, alertDialog)
		{
			@Override
			protected boolean hasFile()
			{
				return fileView.getSelectedItem() != null;
			}

			@Override
			protected boolean needsPassword()
			{
				final File selectedFile = (File) fileView.getSelectedItem();
				return selectedFile != null ? Crypto.OPENSSL_FILE_FILTER.accept(selectedFile) : false;
			}
		};
		passwordView.addTextChangedListener(dialogButtonEnabler);
		fileView.setOnItemSelectedListener(dialogButtonEnabler);

		final CheckBox showView = (CheckBox) alertDialog.findViewById(R.id.import_keys_from_storage_show);
		showView.setOnCheckedChangeListener(new ShowPasswordCheckListener(passwordView));
	}

	private Dialog createExportKeysDialog()
	{
		final View view = getLayoutInflater().inflate(R.layout.export_keys_dialog, null);
		final EditText passwordView = (EditText) view.findViewById(R.id.export_keys_dialog_password);

		final DialogBuilder dialog = new DialogBuilder(this);
		dialog.setTitle(R.string.export_keys_dialog_title);
		dialog.setView(view);
		dialog.setPositiveButton(R.string.export_keys_dialog_button_export, new OnClickListener()
		{
			@Override
			public void onClick(final DialogInterface dialog, final int which)
			{
				final String password = passwordView.getText().toString().trim();
				passwordView.setText(null); // get rid of it asap

				exportPrivateKeys(password);
			}
		});
		dialog.setNegativeButton(R.string.button_cancel, new OnClickListener()
		{
			@Override
			public void onClick(final DialogInterface dialog, final int which)
			{
				passwordView.setText(null); // get rid of it asap
			}
		});
		dialog.setOnCancelListener(new OnCancelListener()
		{
			@Override
			public void onCancel(final DialogInterface dialog)
			{
				passwordView.setText(null); // get rid of it asap
			}
		});
		return dialog.create();
	}

	private void prepareExportKeysDialog(final Dialog dialog)
	{
		final AlertDialog alertDialog = (AlertDialog) dialog;

		final EditText passwordView = (EditText) alertDialog.findViewById(R.id.export_keys_dialog_password);
		passwordView.setText(null);

		final ImportDialogButtonEnablerListener dialogButtonEnabler = new ImportDialogButtonEnablerListener(passwordView, alertDialog);
		passwordView.addTextChangedListener(dialogButtonEnabler);

		final CheckBox showView = (CheckBox) alertDialog.findViewById(R.id.export_keys_dialog_show);
		showView.setOnCheckedChangeListener(new ShowPasswordCheckListener(passwordView));
	}

	private Dialog createChangeLogDialog()
	{
		final DialogBuilder dialog = new DialogBuilder(this);
		dialog.setIcon(R.drawable.ic_menu_warning);
		dialog.setTitle(R.string.wallet_precision_warning_dialog_title);
		dialog.setMessage(R.string.wallet_precision_warning_dialog_msg);
		dialog.setPositiveButton(R.string.button_dismiss, null);
		dialog.setNegativeButton(R.string.button_settings, new DialogInterface.OnClickListener()
		{
			@Override
			public void onClick(final DialogInterface dialog, final int id)
			{
				startActivity(new Intent(WalletActivity.this, PreferencesActivity.class));
			}
		});
		return dialog.create();
	}

	private void checkLowStorageAlert()
	{
		final Intent stickyIntent = registerReceiver(null, new IntentFilter(Intent.ACTION_DEVICE_STORAGE_LOW));
		if (stickyIntent != null)
		{
			final DialogBuilder dialog = new DialogBuilder(this);
			dialog.setIcon(R.drawable.ic_menu_warning);
			dialog.setTitle(R.string.wallet_low_storage_dialog_title);
			dialog.setMessage(R.string.wallet_low_storage_dialog_msg);
			dialog.setPositiveButton(R.string.wallet_low_storage_dialog_button_apps, new DialogInterface.OnClickListener()
			{
				@Override
				public void onClick(final DialogInterface dialog, final int id)
				{
					startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS));
					finish();
				}
			});
			dialog.setNegativeButton(R.string.button_dismiss, null);
			dialog.show();
		}
	}

	private void timeskewAlert(final long diffMinutes)
	{
		final PackageManager pm = getPackageManager();
		final Intent settingsIntent = new Intent(android.provider.Settings.ACTION_DATE_SETTINGS);

		final DialogBuilder dialog = new DialogBuilder(this);
		dialog.setIcon(R.drawable.ic_menu_warning);
		dialog.setTitle(R.string.wallet_timeskew_dialog_title);
		dialog.setMessage(getString(R.string.wallet_timeskew_dialog_msg, diffMinutes));

		if (pm.resolveActivity(settingsIntent, 0) != null)
		{
			dialog.setPositiveButton(R.string.button_settings, new DialogInterface.OnClickListener()
			{
				@Override
				public void onClick(final DialogInterface dialog, final int id)
				{
					startActivity(settingsIntent);
					finish();
				}
			});
		}

		dialog.setNegativeButton(R.string.button_dismiss, null);
		dialog.show();
	}

	private void versionAlert(final int serverVersionCode)
	{
		final PackageManager pm = getPackageManager();
		final Intent marketIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(String.format(Constants.MARKET_APP_URL, getPackageName())));
		final Intent binaryIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(Constants.BINARY_URL));

		final DialogBuilder dialog = new DialogBuilder(this);
		dialog.setIcon(R.drawable.ic_menu_warning);
		dialog.setTitle(R.string.wallet_version_dialog_title);
		dialog.setMessage(getString(R.string.wallet_version_dialog_msg));

		if (pm.resolveActivity(marketIntent, 0) != null)
		{
			dialog.setPositiveButton(R.string.wallet_version_dialog_button_market, new DialogInterface.OnClickListener()
			{
				@Override
				public void onClick(final DialogInterface dialog, final int id)
				{
					startActivity(marketIntent);
					finish();
				}
			});
		}

		if (pm.resolveActivity(binaryIntent, 0) != null)
		{
			dialog.setNeutralButton(R.string.wallet_version_dialog_button_binary, new DialogInterface.OnClickListener()
			{
				@Override
				public void onClick(final DialogInterface dialog, final int id)
				{
					startActivity(binaryIntent);
					finish();
				}
			});
		}

		dialog.setNegativeButton(R.string.button_dismiss, null);
		dialog.show();
	}

	@SuppressLint("StringFormatInvalid")
	private void importPrivateKeys(@Nonnull final File file, @Nonnull final String password)
	{
		try
		{
			final Reader plainReader;
			if (Crypto.OPENSSL_FILE_FILTER.accept(file))
			{
				final BufferedReader cipherIn = new BufferedReader(new InputStreamReader(new FileInputStream(file), Constants.UTF_8));
				final StringBuilder cipherText = new StringBuilder();
				while (true)
				{
					final String line = cipherIn.readLine();
					if (line == null)
						break;

					cipherText.append(line);
				}
				cipherIn.close();

				final String plainText = Crypto.decrypt(cipherText.toString(), password.toCharArray());
				plainReader = new StringReader(plainText);
			}
			else if (WalletUtils.KEYS_FILE_FILTER.accept(file))
			{
				plainReader = new InputStreamReader(new FileInputStream(file), Constants.UTF_8);
			}
			else
			{
				throw new IllegalStateException(file.getAbsolutePath());
			}

			final BufferedReader keyReader = new BufferedReader(plainReader);
			final List<ECKey> importedKeys = WalletUtils.readKeys(keyReader);
			keyReader.close();

			final int numKeysToImport = importedKeys.size();
			final int numKeysImported = wallet.addKeys(importedKeys);

			final DialogBuilder dialog = new DialogBuilder(this);
			final StringBuilder message = new StringBuilder();
			if (numKeysImported > 0)
				message.append(getString(R.string.import_keys_dialog_success_imported, numKeysImported));
			if (numKeysImported < numKeysToImport)
			{
				if (message.length() > 0)
					message.append('\n');
				message.append(getString(R.string.import_keys_dialog_success_existing, numKeysToImport - numKeysImported));
			}
			if (numKeysImported > 0)
			{
				if (message.length() > 0)
					message.append("\n\n");
				message.append(getString(R.string.import_keys_dialog_success_reset));
			}
			dialog.setMessage(message);
			if (numKeysImported > 0)
			{
				dialog.setPositiveButton(R.string.import_keys_dialog_button_reset_blockchain, new DialogInterface.OnClickListener()
				{
					@Override
					public void onClick(final DialogInterface dialog, final int id)
					{
						getWalletApplication().resetBlockchain();
						finish();
					}
				});
				dialog.setNegativeButton(R.string.button_dismiss, null);
			}
			else
			{
				dialog.setNeutralButton(R.string.button_dismiss, null);
			}
			dialog.show();

			log.info("imported " + numKeysImported + " of " + numKeysToImport + " private keys");
		}
		catch (final IOException x)
		{
			final DialogBuilder dialog = new DialogBuilder(this);
			dialog.setIcon(R.drawable.ic_menu_warning);
			dialog.setTitle(R.string.import_export_keys_dialog_failure_title);
			dialog.setMessage(getString(R.string.import_keys_dialog_failure, x.getMessage()));
			dialog.setPositiveButton(R.string.button_dismiss, null);
			dialog.setNegativeButton(R.string.button_retry, new DialogInterface.OnClickListener()
			{
				@Override
				public void onClick(final DialogInterface dialog, final int id)
				{
					showDialog(DIALOG_IMPORT_KEYS);
				}
			});
			dialog.show();

			log.info("problem reading private keys", x);
		}
	}

	@SuppressLint("StringFormatInvalid")
	private void exportPrivateKeys(@Nonnull final String password)
	{
		try
		{
			Constants.EXTERNAL_WALLET_BACKUP_DIR.mkdirs();
			final DateFormat dateFormat = Iso8601Format.newDateFormat();
			dateFormat.setTimeZone(TimeZone.getDefault());
			final File file = new File(Constants.EXTERNAL_WALLET_BACKUP_DIR, Constants.EXTERNAL_WALLET_KEY_BACKUP + "-"
					+ dateFormat.format(new Date()));

			final List<ECKey> keys = new LinkedList<ECKey>();
			for (final ECKey key : wallet.getKeys())
				if (!wallet.isKeyRotating(key))
					keys.add(key);

			final StringWriter plainOut = new StringWriter();
			WalletUtils.writeKeys(plainOut, keys);
			plainOut.close();
			final String plainText = plainOut.toString();

			final String cipherText = Crypto.encrypt(plainText, password.toCharArray());

			final Writer cipherOut = new OutputStreamWriter(new FileOutputStream(file), Constants.UTF_8);
			cipherOut.write(cipherText);
			cipherOut.close();

			final DialogBuilder dialog = new DialogBuilder(this);
			dialog.setMessage(getString(R.string.export_keys_dialog_success, file));
			dialog.setPositiveButton(R.string.export_keys_dialog_button_archive, new OnClickListener()
			{
				@Override
				public void onClick(final DialogInterface dialog, final int which)
				{
					mailPrivateKeys(file);
				}
			});
			dialog.setNegativeButton(R.string.button_dismiss, null);
			dialog.show();

			log.info("exported " + keys.size() + " private keys to " + file);
		}
		catch (final IOException x)
		{
			final DialogBuilder dialog = new DialogBuilder(this);
			dialog.setIcon(R.drawable.ic_menu_warning);
			dialog.setTitle(R.string.import_export_keys_dialog_failure_title);
			dialog.setMessage(getString(R.string.export_keys_dialog_failure, x.getMessage()));
			dialog.setNeutralButton(R.string.button_dismiss, null);
			dialog.show();

			log.error("problem writing private keys", x);
		}
	}

	private void mailPrivateKeys(@Nonnull final File file)
	{
		final Intent intent = new Intent(Intent.ACTION_SEND);
		intent.putExtra(Intent.EXTRA_SUBJECT, getString(R.string.export_keys_dialog_mail_subject));
		intent.putExtra(Intent.EXTRA_TEXT,
				getString(R.string.export_keys_dialog_mail_text) + "\n\n" + String.format(Constants.WEBMARKET_APP_URL, getPackageName()) + "\n\n"
						+ Constants.SOURCE_URL + '\n');
		intent.setType("x-"+ CoinDefinition.coinName.toLowerCase()+"/private-keys");
		intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
		Uri fileUri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", file);
		intent.putExtra(Intent.EXTRA_STREAM, fileUri);

		try
		{
			startActivity(Intent.createChooser(intent, getString(R.string.export_keys_dialog_mail_intent_chooser)));
			log.info("invoked chooser for archiving private keys");
		}
		catch (final Exception x)
		{
			longToast(R.string.export_keys_dialog_mail_intent_failed);
			log.error("archiving private keys failed", x);
		}
	}
}
