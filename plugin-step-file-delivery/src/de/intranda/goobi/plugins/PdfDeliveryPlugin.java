package de.intranda.goobi.plugins;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.URL;
import java.util.Date;
import java.util.HashMap;
import java.util.Properties;

import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.Session;
import javax.mail.Transport;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeBodyPart;
import javax.mail.internet.MimeMessage;
import javax.mail.internet.MimeMultipart;

import net.xeoh.plugins.base.annotations.PluginImplementation;

import org.apache.commons.httpclient.HttpClient;
import org.apache.commons.httpclient.HttpStatus;
import org.apache.commons.httpclient.methods.GetMethod;
import org.apache.commons.io.FileUtils;
import org.apache.log4j.Logger;
import org.goobi.production.cli.helper.WikiFieldHelper;
import org.goobi.production.enums.PluginGuiType;
import org.goobi.production.enums.PluginType;
import org.goobi.production.enums.StepReturnValue;
import org.goobi.production.plugin.interfaces.IPlugin;
import org.goobi.production.plugin.interfaces.IStepPlugin;

import ugh.exceptions.DocStructHasNoTypeException;
import ugh.exceptions.MetadataTypeNotAllowedException;
import ugh.exceptions.PreferencesException;
import ugh.exceptions.ReadException;
import ugh.exceptions.TypeNotAllowedForParentException;
import ugh.exceptions.WriteException;

import de.sub.goobi.Beans.Prozess;
import de.sub.goobi.Beans.Prozesseigenschaft;
import de.sub.goobi.Beans.Schritt;
import de.sub.goobi.Export.download.ExportMets;
import de.sub.goobi.Metadaten.MetadatenVerifizierungWithoutHibernate;
import de.sub.goobi.Persistence.ProzessDAO;
import de.sub.goobi.Persistence.apache.ProcessManager;
import de.sub.goobi.Persistence.apache.StepObject;
import de.sub.goobi.config.ConfigMain;
import de.sub.goobi.config.ConfigPlugins;
import de.sub.goobi.helper.Helper;
import de.sub.goobi.helper.exceptions.DAOException;
import de.sub.goobi.helper.exceptions.ExportFileException;
import de.sub.goobi.helper.exceptions.SwapException;
import de.sub.goobi.helper.exceptions.UghHelperException;
import de.sub.goobi.helper.encryption.MD5;

@PluginImplementation
public class PdfDeliveryPlugin implements IStepPlugin, IPlugin {
	private static final Logger logger = Logger.getLogger(PdfDeliveryPlugin.class);

	private String pluginname = "PdfDelivery";
	// private Schritt step;
	private Prozess process;
	private String returnPath;

	private static final String PROPERTYTITLE = "PDFURL";

	// TODO generate value
	private String internalServletPath = "http://localhost:8080/Goobi19";

	@Override
	public PluginType getType() {
		return PluginType.Step;
	}

	@Override
	public String getTitle() {
		return pluginname;
	}

	@Override
	public String getDescription() {
		return pluginname;
	}

	@Override
	public void initialize(Schritt step, String returnPath) {
		// this.step = step;
		this.process = step.getProzess();
		this.returnPath = returnPath;
	}

	@Override
	public void initialize(StepObject stepobject, String returnPath) {
		try {
			// this.step = new SchrittDAO().get(stepobject.getId());
			this.process = new ProzessDAO().get(stepobject.getProcessId());
		} catch (DAOException e) {

		}
		this.returnPath = returnPath;
	}

	@Override
	public boolean execute() {
		// - Metadaten validieren

		// TODO sicherstellen das filegroup PDF erzeugt und in im gcs für pdf eingestellt wurde
		MetadatenVerifizierungWithoutHibernate mv = new MetadatenVerifizierungWithoutHibernate();
		if (!mv.validate(process)) {
			createMessages(Helper.getTranslation("PluginErrorInvalidMetadata"), null);
			return false;
		}
		String tempfolder = ConfigMain.getParameter("tempfolder", "/opt/digiverso/goobi/temp/");
		MD5 md5 = new MD5(process.getTitel());
		// - umbenennen in unique Namen
		File pdfFile = new File(tempfolder, System.currentTimeMillis() + md5.getMD5() + "_" + process.getTitel() + ".pdf");
		String metsfile = tempfolder + process.getTitel() + "_mets.xml";
		// - PDF erzeugen
		GetMethod method = null;
		try {
			ExportMets em = new ExportMets();

			em.startExport(process, tempfolder);

			URL goobiContentServerUrl = null;
			String contentServerUrl = ConfigMain.getParameter("goobiContentServerUrl");

			Integer contentServerTimeOut = ConfigMain.getIntParameter("goobiContentServerTimeOut", 60000);
			if (contentServerUrl == null || contentServerUrl.length() == 0) {
				contentServerUrl = this.internalServletPath + "/gcs/gcs?action=pdf&metsFile=file://";
			}
			goobiContentServerUrl = new URL(contentServerUrl + metsfile);

			HttpClient httpclient = new HttpClient();
			logger.debug("Retrieving: " + goobiContentServerUrl.toString());
			method = new GetMethod(goobiContentServerUrl.toString());

			method.getParams().setParameter("http.socket.timeout", contentServerTimeOut);
			int statusCode = httpclient.executeMethod(method);
			if (statusCode != HttpStatus.SC_OK) {
				logger.error("HttpStatus nicht ok", null);
				createMessages(Helper.getTranslation("PluginErrorPDFCreationError"), null);
				return false;
			}

			InputStream inStream = method.getResponseBodyAsStream();
			BufferedInputStream bis = new BufferedInputStream(inStream);
			FileOutputStream fos = new FileOutputStream(pdfFile);
			byte[] bytes = new byte[8192];
			int count = bis.read(bytes);
			while (count != -1 && count <= 8192) {
				fos.write(bytes, 0, count);
				count = bis.read(bytes);
			}
			if (count != -1) {
				fos.write(bytes, 0, count);
			}
			fos.close();
			bis.close();

			// TODO individuelle Fehlermeldungen
		} catch (PreferencesException e) {
			createMessages(Helper.getTranslation("PluginErrorInvalidMetadata"), e);
			return false;
		} catch (WriteException e) {
			createMessages(Helper.getTranslation("PluginErrorInvalidMetadata"), e);
			return false;
		} catch (DocStructHasNoTypeException e) {
			createMessages(Helper.getTranslation("PluginErrorInvalidMetadata"), e);
			return false;
		} catch (MetadataTypeNotAllowedException e) {
			createMessages(Helper.getTranslation("PluginErrorInvalidMetadata"), e);
			return false;
		} catch (ExportFileException e) {
			createMessages(Helper.getTranslation("PluginErrorInvalidMetadata"), e);
			return false;
		} catch (UghHelperException e) {
			createMessages(Helper.getTranslation("PluginErrorInvalidMetadata"), e);
			return false;
		} catch (ReadException e) {
			createMessages(Helper.getTranslation("PluginErrorInvalidMetadata"), e);
			return false;
		} catch (SwapException e) {
			createMessages(Helper.getTranslation("PluginErrorInvalidMetadata"), e);
			return false;
		} catch (DAOException e) {
			createMessages(Helper.getTranslation("PluginErrorInvalidMetadata"), e);
			return false;
		} catch (TypeNotAllowedForParentException e) {
			createMessages(Helper.getTranslation("PluginErrorInvalidMetadata"), e);
			return false;
		} catch (IOException e) {
			createMessages(Helper.getTranslation("PluginErrorInvalidMetadata"), e);
			return false;
		} catch (InterruptedException e) {
			createMessages(Helper.getTranslation("PluginErrorInvalidMetadata"), e);
			return false;
		} finally {
			if (method != null) {
				method.releaseConnection();
			}
		}

		// - an anderen Ort kopieren
		String destination = ConfigPlugins.getPluginConfig(this).getString("destinationFolder", "/opt/digiverso/pdfexport/");
		String donwloadServer = ConfigPlugins.getPluginConfig(this).getString("donwloadServer", "http://localhost:8080/Goobi19/");
		String downloadUrl = donwloadServer + pdfFile.getName();
		try {
			FileUtils.copyFileToDirectory(pdfFile, new File(destination));
			FileUtils.deleteQuietly(pdfFile);
			FileUtils.deleteQuietly(new File(metsfile));
		} catch (IOException e) {
			createMessages(Helper.getTranslation("PluginErrorIOError"), e);
			return false;
		}

		// - Name/Link als Property speichern

		boolean matched = false;
		for (Prozesseigenschaft pe : process.getEigenschaftenList()) {
			if (pe.getTitel().equals(PROPERTYTITLE)) {
				pe.setWert(downloadUrl);
				matched = true;
				break;
			}
		}

		if (!matched) {
			Prozesseigenschaft pe = new Prozesseigenschaft();
			pe.setTitel(PROPERTYTITLE);
			pe.setWert(downloadUrl);
			process.getEigenschaften().add(pe);
			pe.setProzess(process);
		}

		try {
			new ProzessDAO().save(process);
		} catch (DAOException e) {
			createMessages(Helper.getTranslation("fehlerNichtSpeicherbar"), e);
			return false;
		}

		// - mail versenden
		String[] mail = { "robert.sehr@intranda.com", "jan@intranda.com" };
		try {
			postMail(mail, "pdf download", downloadUrl);
		} catch (UnsupportedEncodingException e) {
			createMessages("PluginErrorMailError", e);
			return false;
		} catch (MessagingException e) {
			createMessages("PluginErrorMailError", e);
			return false;
		}

		return true;
	}

	@Override
	public String cancel() {
		return returnPath;

	}

	@Override
	public String finish() {
		return returnPath;

	}

	@Override
	public HashMap<String, StepReturnValue> validate() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public Schritt getStep() {
		// TODO
		return null;
	}

	@Override
	public PluginGuiType getPluginGuiType() {
		return PluginGuiType.NONE;
	}

	private void createMessages(String message, Exception e) {
		if (e != null) {
			Helper.setFehlerMeldung(message, e);
			ProcessManager.addLogfile(WikiFieldHelper.getWikiMessage(process.getWikifield(), "error", message), process.getId());
			logger.error(message, e);
		} else {
			Helper.setFehlerMeldung(message);
			ProcessManager.addLogfile(WikiFieldHelper.getWikiMessage(process.getWikifield(), "error", message), process.getId());
			logger.error(message);
		}

	}

	public void postMail(String recipients[], String subject, String message) throws MessagingException, UnsupportedEncodingException {
		boolean debug = false;

		String SMTP_SERVER = ConfigPlugins.getPluginConfig(this).getString("SMTP_SERVER", "mail.intranda.com");
		String SMTP_USER = ConfigPlugins.getPluginConfig(this).getString("SMTP_USER", "TODO");
		String SMTP_PASSWORD = ConfigPlugins.getPluginConfig(this).getString("SMTP_PASSWORD", "TODO");
		String SMTP_USE_STARTTLS = ConfigPlugins.getPluginConfig(this).getString("SMTP_USE_STARTTLS", "0");
		String SMTP_USE_SSL = ConfigPlugins.getPluginConfig(this).getString("SMTP_USE_SSL", "1");
		String SENDER_ADDRESS = ConfigPlugins.getPluginConfig(this).getString("SENDER_ADDRESS", "TODO");

		// Set the host smtp address
		Properties props = new Properties();
		if (SMTP_USE_STARTTLS != null && SMTP_USE_STARTTLS.equals("1")) {
			props.setProperty("mail.transport.protocol", "smtp");
			props.setProperty("mail.smtp.auth", "true");
			props.setProperty("mail.smtp.port", "25");
			props.setProperty("mail.smtp.host", SMTP_SERVER);
			props.setProperty("mail.smtp.ssl.trust", "*");
			props.setProperty("mail.smtp.starttls.enable", "true");
			props.setProperty("mail.smtp.starttls.required", "true");
		} else if (SMTP_USE_SSL != null && SMTP_USE_SSL.equals("1")) {
			props.setProperty("mail.transport.protocol", "smtp");
			props.setProperty("mail.smtp.host", SMTP_SERVER);
			props.setProperty("mail.smtp.auth", "true");
			props.setProperty("mail.smtp.port", "465");
			props.setProperty("mail.smtp.ssl.enable", "true");
			props.setProperty("mail.smtp.ssl.trust", "*");

		} else {
			props.setProperty("mail.transport.protocol", "smtp");
			props.setProperty("mail.smtp.auth", "true");
			props.setProperty("mail.smtp.port", "25");
			props.setProperty("mail.smtp.host", SMTP_SERVER);
		}

		Session session = Session.getDefaultInstance(props, null);
		session.setDebug(debug);
		Message msg = new MimeMessage(session);

		InternetAddress addressFrom = new InternetAddress(SENDER_ADDRESS, "Robert Sehr");
		msg.setFrom(addressFrom);

		InternetAddress[] addressTo = new InternetAddress[recipients.length];
		for (int i = 0; i < recipients.length; i++) {
			addressTo[i] = new InternetAddress(recipients[i]);
		}
		msg.setRecipients(Message.RecipientType.TO, addressTo);

		// Optional : You can also set your custom headers in the Email if you
		// Want
		// msg.addHeader("MyHeaderName", "myHeaderValue");

		msg.setSubject(subject);

		MimeBodyPart messagePart = new MimeBodyPart();
		messagePart.setText(message, "utf-8");
		messagePart.setHeader("Content-Type", "text/plain; charset=\"utf-8\"");
		MimeMultipart multipart = new MimeMultipart();
		multipart.addBodyPart(messagePart);

		msg.setContent(multipart);
		msg.setSentDate(new Date());

		Transport transport = session.getTransport();
		transport.connect(SMTP_USER, SMTP_PASSWORD);
		transport.sendMessage(msg, msg.getRecipients(Message.RecipientType.TO));
		transport.close();
	}

}
