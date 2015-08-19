package org.datadryad.submission;

import org.apache.commons.lang.StringUtils;
import org.apache.log4j.Logger;
import org.dspace.JournalUtils;
import org.dspace.content.authority.Concept;
import org.dspace.core.ConfigurationManager;
import org.dspace.core.Context;
import org.dspace.core.Email;
import org.dspace.core.I18nUtil;
import org.dspace.submit.utils.DryadJournalSubmissionUtils;
import org.jdom.Document;
import org.jdom.input.SAXBuilder;
import org.jdom.output.Format;
import org.jdom.output.XMLOutputter;

import javax.mail.*;
import javax.mail.internet.MimeMessage;
import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.*;
import java.sql.SQLException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Email processing servlet.
 *
 * @author Kevin S. Clarke <ksclarke@gmail.com>
 */
@SuppressWarnings("serial")
public class DryadEmailSubmission extends HttpServlet {

    private static final Logger LOGGER = Logger.getLogger(DryadEmailSubmission.class);

    private static String PROPERTIES_PROPERTY = "dryad.properties.filename";

    private static String EMAIL_TEMPLATE = "journal_submit_error";

    // Timer for scheduled harvesting of emails
    private Timer myEmailHarvester;

    private static DryadGmailService dryadGmailService;

    /**
     * UPDATE: GET only works for authorization and testing Gmail API.
     * Handles the HTTP <code>GET</code> method by informing the caller that
     * <code>GET</code> is not supported, only <code>POST</code>.
     *
     * @param aRequest  A servlet request
     * @param aResponse A servlet response
     * @throws ServletException If a servlet-specific error occurs
     * @throws IOException      If an I/O error occurs
     */
    @Override
    protected void doGet(HttpServletRequest aRequest,
                         HttpServletResponse aResponse) throws ServletException, IOException {
        String requestURI = aRequest.getRequestURI();
        if (requestURI.contains("authorize")) {
            String queryString = aRequest.getQueryString();
            if (aRequest.getQueryString() == null) {
                // If we've never gotten a credential from here before, do this.
                String urlString = dryadGmailService.getAuthorizationURLString();
                LOGGER.info("userID "+dryadGmailService.getMyUserID());
                aResponse.sendRedirect(urlString);
            }
            else if (queryString.contains("code=")) {
                String code = queryString.substring(queryString.indexOf("=")+1);
                LOGGER.info("authorizing with code "+ code);
                // Generate Credential using retrieved code.
                dryadGmailService.authorize(code);
            }
            return;
        } else if (requestURI.contains("test")) {
            try {
                LOGGER.info(DryadGmailService.testMethod());
            } catch (IOException e) {
                LOGGER.info(e.getMessage());
                throw new RuntimeException(e.getMessage());
            }
        } else if (requestURI.contains("retrieve")) {
            LOGGER.info("manually running DryadGmailService");
            retrieveMail();
        } else {
            aResponse.sendError(HttpServletResponse.SC_METHOD_NOT_ALLOWED,
                    "GET is not supported, you must POST to this service");
        }
    }

    /**
     * Handles the HTTP <code>POST</code> method.
     *
     * @param aRequest A servlet request
     * @param aResponse A servlet response
     * @throws ServletException If a servlet-specific error occurs
     * @throws IOException      If an I/O error occurs
     */
    @Override
    protected void doPost(HttpServletRequest aRequest,
                          HttpServletResponse aResponse) throws ServletException, IOException {

        LOGGER.info("Request encoding: " + aRequest.getCharacterEncoding());

        try {
            PrintWriter toBrowser = getWriter(aResponse);
            InputStream postBody = aRequest.getInputStream();
            Session session = Session.getInstance(new Properties());
            MimeMessage mime = new MimeMessage(session, postBody);
            String xml = processMimeMessage(mime);
            // Nice to return our result in case we are debugging output
            toBrowser.println(xml);
            toBrowser.close();
        } catch (Exception details) {
            sendEmailIfConfigured(details);

            if (details instanceof SubmissionException) {
                throw (SubmissionException) details;
            } else {
                throw new SubmissionException(details);
            }
        }
    }

    private void retrieveMail () {
        LOGGER.info ("retrieving mail with label '" + ConfigurationManager.getProperty("submit.journal.email.label") + "'");
        try {
            ArrayList<MimeMessage> messages = DryadGmailService.processJournalEmails();
            if (messages != null) {
                LOGGER.info("retrieved " + messages.size() + " messages");
                for (MimeMessage message : messages) {
                    try {
                        processMimeMessage(message);
                    } catch (Exception details) {
                        LOGGER.info("Exception thrown while processing MIME message: " + details.getMessage() + ", " + details.getClass().getName());
                    }
                }
            }
        } catch (IOException e) {
            LOGGER.info("Exception thrown: " + e.getMessage() + ", " + e.getClass().getName());
        }
    }
    /**
     * This method was added because multipart content may contain other multipart content; this
     * needs to dig down until some text is found
     *
     * @param part Either the full message or a part
     * @return a part with text/plain content
     * @throws MessagingException
     * @throws IOException
     */
    private Part getTextPart(Part part) throws MessagingException, IOException {
        String contentType = part.getContentType();

        if (contentType != null && contentType.startsWith("text/plain")) {
            return part;   //
        } else if (contentType != null &&
                contentType.startsWith("multipart/alternative") ||
                contentType.startsWith("multipart/mixed")) {    //could just use multipart as prefix, but what does this cover?
            Multipart mp = (Multipart) part.getContent();
            for (int i = 0, count = mp.getCount(); i < count; i++) {
                Part p = mp.getBodyPart(i);
                Part pt = getTextPart(p);
                if (pt != null)
                    return pt;
            }
        }
        return null;
    }

    @Override
    public void init(ServletConfig aConfig) throws ServletException {
        super.init(aConfig);

        // First, get our properties from the configuration file
        Properties props = new Properties();
        String propFileName;

        // If we're running in the Jetty/Maven plugin we set properties here
        if ((propFileName = System.getProperty(PROPERTIES_PROPERTY)) != null) {
            try {
                props.load(new InputStreamReader(new FileInputStream(propFileName), "UTF-8"));
            } catch (IOException details) {
                throw new SubmissionException(details);
            }

        }
        // Otherwise, we're running in the standard DSpace Tomcat
        else {
            if (!ConfigurationManager.isConfigured()) {
                // not configured
                // Get config parameter
                String config = getServletContext().getInitParameter("dspace.config");

                // Load in DSpace config
                ConfigurationManager.loadConfig(config);
            }

            dryadGmailService = new DryadGmailService();
        }

        LOGGER.debug("scheduling email harvesting");
        myEmailHarvester = new Timer();
        // schedule email harvesting to happen once an hour
        int timerInterval = Integer.parseInt(ConfigurationManager.getProperty("submit.journal.email.timer"));
        myEmailHarvester.schedule(new DryadEmailSubmissionHarvester(), 0, 1000 * timerInterval);
    }

    /**
     * If we're running within DSpace (and not the Maven/Jetty test instance),
     * we can send email through there using their template system.
     *
     * @param aException An exception that was thrown in the process of
     *                   receiving a journal submission
     * @throws SubmissionException
     * @throws IOException
     */
    private void sendEmailIfConfigured(Exception aException)
            throws SubmissionException {
        try {
            if (ConfigurationManager.isConfigured()) {
                String exceptionMessage = aException.toString();
                StringBuilder message = new StringBuilder(exceptionMessage);
                String admin = ConfigurationManager.getProperty("mail.admin");
                String logDir = ConfigurationManager.getProperty("log.dir");
                Email email = ConfigurationManager.getEmail(I18nUtil.getEmailFilename(Locale.getDefault(), EMAIL_TEMPLATE));

                if (logDir == null || admin == null) {
                    throw new SubmissionException(
                            "DSpace mail is not properly configured");
                }

                for (StackTraceElement trace : aException.getStackTrace()) {
                    message.append(System.getProperty("line.separator"));
                    message.append("at ").append(trace.getClass()).append("(");
                    message.append(trace.getFileName()).append(":");
                    message.append(trace.getLineNumber()).append(")");
                }

                email.addRecipient(admin);
                email.addArgument(message);
                email.addArgument(logDir + "/journal-submit.log");
                email.send();
            }
        } catch (Exception details) {
            if (details instanceof SubmissionException) {
                throw (SubmissionException) details;
            } else {
                throw new SubmissionException(details);
            }
        }
    }

    private String processMimeMessage (MimeMessage mime) throws Exception {
        LOGGER.info("MIME contentType/ID/encoding: " + mime.getContentType()
                + " " + mime.getContentID() + " " + mime.getEncoding());

        Part part = getTextPart(mime);
        if (part == null) {
            throw new SubmissionException("Unexpected email type: "
                    + mime.getContent().getClass().getName() + " reported content-type was " + mime.getContentType());
        }

        String message;
        if (mime.getEncoding() != null) {
            message = (String) part.getContent();
        } else {
            InputStream in = part.getInputStream();
            InputStreamReader isr = new InputStreamReader(in, "UTF-8");
            BufferedReader br = new BufferedReader(isr);
            StringBuilder builder = new StringBuilder();
            String line;

            while ((line = br.readLine()) != null) {
                builder.append(line);
                builder.append(System.getProperty("line.separator"));
            }

            message = builder.toString();
        }

        // Then we can hand off to implementer of EmailParser
        ParsingResult result = parseMessage(message, mime.getFrom());

        if (result.getStatus() != null) {
            throw new SubmissionException(result.getStatus());
        }

        if (result.hasFlawedId()) {
            throw new SubmissionException("Result ID is flawed: "
                    + result.getSubmissionId());
        }

        return parseToXML(result);

    }

    private String parseToXML (ParsingResult result) {

        // We'll use JDOM b/c the libs are already included in DSpace
        SAXBuilder saxBuilder = new SAXBuilder();
        String xml = result.getSubmissionData().toString();

        // FIXME: Individual Email parsers don't supply a root element
        // Our JDOM classes below will add version, encoding, etc.
        xml = "<DryadEmailSubmission>"
                + System.getProperty("line.separator") + xml
                + "</DryadEmailSubmission>";

        StringReader xmlReader = new StringReader(xml);

        try {
            Format format = Format.getPrettyFormat();
            XMLOutputter toFile = new XMLOutputter(format);
            Document doc = saxBuilder.build(xmlReader);
            String journalCode = JournalUtils.cleanJournalCode(result.getJournalCode());

            LOGGER.debug("Getting metadata dir for " + journalCode);

            Context context = new Context();
            Concept journalConcept = JournalUtils.getJournalConceptByShortID(context, journalCode);
            File dir = new File(JournalUtils.getMetadataDir(journalConcept));

            String submissionId = result.getSubmissionId();
            String filename = DryadJournalSubmissionUtils.escapeFilename(submissionId + ".xml");
            File file = new File(dir, filename);
            LOGGER.info ("wrote xml to file " + file.getAbsolutePath());
            FileOutputStream out = new FileOutputStream(file);
            OutputStreamWriter writer = new OutputStreamWriter(out, "UTF-8");

            // And we write the output to our submissions directory
            toFile.output(doc, new BufferedWriter(writer));
        } catch (Exception details) {
            LOGGER.debug("failed to write to file: xml content would have been: " + xml);
            throw new SubmissionRuntimeException(details);
        }
        return xml;
    }


    private ParsingResult parseMessage(String aMessage, Address[] addresses)
            throws SubmissionException {
        List<String> lines = new ArrayList<String>();
        Scanner emailScanner = new Scanner(aMessage);
        String journalName = null;
        String journalCode = null;
        Pattern journalCodePattern = Pattern.compile("^\\s*>*\\s*(Journal Code):\\s*(.+)");
        Pattern journalNamePattern = Pattern.compile("^\\s*>*\\s*(JOURNAL|Journal Name):\\s*(.+)");
        boolean dryadContentStarted = false;
        while (emailScanner.hasNextLine()) {
            String line = emailScanner.nextLine();

            if (StringUtils.stripToEmpty(line).equals("")) {
                continue;
            } else {
                Matcher journalCodeMatcher = journalCodePattern.matcher(line);
                if (journalCodeMatcher.find()) {
                    journalCode = StringUtils.stripToEmpty(journalCodeMatcher.group(2));
                    // strip out leading NBSP if present
                    if (journalCode.codePointAt(0) == 160) {
                        journalCode = journalCode.substring(1);
                    }
                    dryadContentStarted = true;
                }

                Matcher journalNameMatcher = journalNamePattern.matcher(line);
                if (journalNameMatcher.find()) {
                    journalName = StringUtils.stripToEmpty(journalNameMatcher.group(2));
                    if (journalName.codePointAt(0) == 160) {          //Journal of Heredity has started inserting NBSP in several fields, including journal title
                        journalName = journalName.substring(1);
                    }
                    dryadContentStarted = true;
                }

                if (dryadContentStarted) {
                    lines.add(line);
                }
                // Stop reading lines at EndDryadContent
                if (line.contains("EndDryadContent")) {
                    break;
                }
            }
        }
        // After reading the entire message, attempt to find the PartnerJournal object by
        // Journal Code.  If Journal Code is not present, fall back to Journal Name
        if (journalCode == null) {
            LOGGER.debug("Journal Code not found in message, trying by journal name: " + journalName);
            if (journalName != null) {
                Context context = null;
                Concept concept = null;
                try {
                    context = new Context();
                    concept = JournalUtils.getJournalConceptByName(context, journalName);
                } catch (SQLException e) {
                    throw new SubmissionException(e);
                }
                journalCode =  JournalUtils.getJournalShortID(concept);

            } else {
                throw new SubmissionException("Journal Code not present and Journal Name not found in message");
            }
            if (journalCode == null) {
                throw new SubmissionException("Journal Name " + journalName + " did not match a known Journal Code");
            }
        }

        if (journalCode != null) {
            // find the associated concept and initialize the parser variable.
            Context context = null;
            Concept concept = null;
            journalCode = JournalUtils.cleanJournalCode(journalCode);
            try {
                context = new Context();
                concept = JournalUtils.getJournalConceptByShortID(context, journalCode);
            } catch (SQLException e) {
                throw new SubmissionException(e);
            }
            String parsingScheme = JournalUtils.getParsingScheme(concept);
            PartnerJournal partnerJournal =  new PartnerJournal(concept.getName());
            partnerJournal.setParsingScheme(parsingScheme);
            EmailParser parser =  partnerJournal.getParser();

            if (parser != null) {
                ParsingResult result = parser.parseMessage(lines);

                result.setJournalCode(journalCode);
                result.setJournalName(journalName);

                // Do this because this is what the parsers are expecting to
                // build the corresponding author field from
                for (Address address : addresses) {
                    result.setSenderEmailAddress(address.toString());
                }

                return result;
            } else {
                throw new SubmissionException("Journal " + journalCode + " not found in configuration");
            }
        } else {
            throw new SubmissionException("Journal code not found in message");
        }

    }

    private Map<String, PartnerJournal> validate(
            Map<String, PartnerJournal> aJournalMap) {
        Map<String, PartnerJournal> results = new HashMap<String, PartnerJournal>();

        for (String journalCode : aJournalMap.keySet()) {
            PartnerJournal journal = aJournalMap.get(journalCode);
            if (!journal.isComplete()) {
                throw new SubmissionRuntimeException(journal.getName()
                        + "'s configuration isn't complete");
            } else {
                // now store our metadata by the journal name instead of code
                results.put(journalCode, journal);
            }

            LOGGER.debug("Registered journal: " + journal.toString());
        }

        return results;
    }

    private Map<String, String> mapJournalNamesToCodes(
            Map<String, PartnerJournal> aJournalMap) {
        Map<String, String> results = new HashMap<String, String>();

        for (String journalCode : aJournalMap.keySet()) {
            PartnerJournal journal = aJournalMap.get(journalCode);
            results.put(journal.getName(), journalCode);
        }
        return results;
    }

    /**
     * Returns a short description of the servlet.
     *
     * @return a String containing servlet description
     */
    @Override
    public String getServletInfo() {
        return "Pre-processor for email from Dryad partner journals.";
    }

    /**
     * Returns a PrintWriter with the correct character encoding set.
     *
     * @param aResponse In which to set the character encoding
     * @return A <code>PrintWriter</code> to send text through
     * @throws IOException If there is trouble getting a writer
     */
    private PrintWriter getWriter(HttpServletResponse aResponse)
            throws IOException {
        aResponse.setContentType("xml/application; charset=UTF-8");
        return aResponse.getWriter();
    }

    private class DryadEmailSubmissionHarvester extends TimerTask {
        @Override
        public void run() {
            retrieveMail();
        }
    }
}
