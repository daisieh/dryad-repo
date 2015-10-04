package org.dspace;

import org.apache.log4j.Logger;
import org.datadryad.rest.converters.ManuscriptToLegacyXMLConverter;
import org.datadryad.rest.models.Manuscript;
import org.datadryad.rest.models.Organization;
import org.datadryad.rest.storage.StorageException;
import org.datadryad.rest.storage.StoragePath;
import org.datadryad.rest.storage.rdbms.ManuscriptDatabaseStorageImpl;
import org.datadryad.rest.storage.rdbms.OrganizationDatabaseStorageImpl;
import org.dspace.content.Collection;
import org.dspace.content.DCValue;
import org.dspace.content.Item;
import org.dspace.content.authority.AuthorityMetadataValue;
import org.dspace.content.authority.Concept;
import org.dspace.content.authority.Scheme;
import org.dspace.core.ConfigurationManager;
import org.dspace.core.Context;
import org.dspace.workflow.DryadWorkflowUtils;

import javax.xml.bind.JAXBException;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * User: lantian @ atmire . com
 * Date: 9/12/14
 * Time: 12:18 PM
 */
public class JournalUtils {

    private static Logger log = Logger.getLogger(JournalUtils.class);
    public static final String FULLNAME = "fullname";
    public static final String METADATADIR = "metadataDir";
    public static final String INTEGRATED = "integrated";
    public static final String PUBLICATION_BLACKOUT = "publicationBlackout";
    public static final String NOTIFY_ON_REVIEW = "notifyOnReview";
    public static final String NOTIFY_ON_ARCHIVE = "notifyOnArchive";
    public static final String JOURNAL_ID = "journalID";
    public static final String SUBSCRIPTION_PAID = "subscriptionPaid";

    public enum RecommendedBlackoutAction {
        BLACKOUT_TRUE
        , BLACKOUT_FALSE
        , JOURNAL_NOT_INTEGRATED
    }

    public static final java.util.Map<String, Map<String, String>> journalProperties = new HashMap<String, Map<String, String>>();

    static{
        Context context = null;

        try {
            context = new Context();
            Scheme scheme = Scheme.findByIdentifier(context, ConfigurationManager.getProperty("solrauthority.searchscheme.prism_publicationName"));
            Concept[] concepts = scheme.getConcepts();
            //todo:add the journal order
            //String journalTypes = properties.getProperty("journal.order");
            for(Concept concept:concepts){
                String key = concept.getPreferredLabel();
                ArrayList<AuthorityMetadataValue> metadataValues = concept.getMetadata();
                Map<String, String> map = new HashMap<String, String>();
                for(AuthorityMetadataValue metadataValue : metadataValues){
                    if(metadataValue.qualifier==null){
                        map.put(metadataValue.element,metadataValue.value);
                    }
                    else
                    {
                        map.put(metadataValue.element+'.'+metadataValue.qualifier,metadataValue.value);
                    }
                    if(key!=null&&key.length()>0){
                        journalProperties.put(key, map);
                    }
                }
            }
            context.complete();
        }catch (Exception e) {
            if(context!=null)
            {
                context.abort();
            }
            log.error("Error while loading journal properties", e);
        }
    }
    public static Concept[] getJournalConcepts(Context context) throws SQLException {
        Scheme scheme = Scheme.findByIdentifier(context, ConfigurationManager.getProperty("solrauthority.searchscheme.prism_publicationName"));
        return scheme.getConcepts();
    }

    public static Concept getJournalConceptById(Context context, String authorityId) throws SQLException {
        try
        {
            List<Concept> concepts = Concept.findByJournalID(context, authorityId);
            if (concepts.size() > 0) {
                Concept concept = concepts.get(0);
                return concept;
            }
        }
        catch(Exception e)
        {
            if(log.isDebugEnabled())
                log.error(e.getMessage(),e);
            else
                log.error(e);
        }

        return null;
    }

    public static Concept getJournalConceptByName(Context context, String journalName) throws SQLException {
        Scheme scheme = Scheme.findByIdentifier(context, ConfigurationManager.getProperty("solrauthority.searchscheme.prism_publicationName"));
        try
        {
            return Concept.findByPreferredLabel(context,journalName,scheme.getID())[0];
        }
        catch(Exception e)
        {
            if(log.isDebugEnabled())
                log.error(e.getMessage(),e);
            else
                log.error(e);
        }

        return null;
    }


    public static Concept getJournalConceptByShortID(Context context, String journalShortID) throws SQLException {
        Scheme scheme = Scheme.findByIdentifier(context, ConfigurationManager.getProperty("solrauthority.searchscheme.prism_publicationName"));
        try
        {
            // TODO : a better query would be in Concept and filter at the db level
            for(Concept concept : scheme.getConcepts())
            {
                String shortId = getJournalShortID(concept);
                if(shortId != null && shortId.equals(journalShortID))
                    return concept;
            }
        }
        catch(Exception e)
        {
            if(log.isDebugEnabled())
                log.error(e.getMessage(),e);
            else
                log.error(e);
        }

        return null;
    }

    public static Concept[] getJournalConcept(Context context,String journal) throws SQLException {
        Scheme scheme = Scheme.findByIdentifier(context, ConfigurationManager.getProperty("solrauthority.searchscheme.prism_publicationName"));

        if(journal==null||journal.length()==0)
            return getJournalConcepts(context);
        return Concept.findByPreferredLabel(context,journal,scheme.getID());

    }

    public static String getIntegrated(Concept concept) {
        AuthorityMetadataValue[] vals = concept.getMetadata("journal", "integrated", null, Item.ANY);
        if(vals != null && vals.length > 0)
            return vals[0].value;

        return null;
    }

    public static boolean getBooleanIntegrated(Concept concept) {
        AuthorityMetadataValue[] vals = concept.getMetadata("journal", "integrated", null, Item.ANY);
        if(vals != null && vals.length > 0)
            return vals[0].value.toLowerCase().equals("true");

        return false;
    }

    public static String getSubscriptionPaid(Concept concept) {
        AuthorityMetadataValue[] vals = concept.getMetadata("journal","subscriptionPaid",null, Item.ANY);
        if(vals != null && vals.length > 0)
            return vals[0].value;

        return null;
    }

    public static String getMetadataDir(Concept concept) {
        try
        {
            AuthorityMetadataValue[] vals = concept.getMetadata("journal","metadataDir",null, Item.ANY);
            if(vals != null && vals.length > 0)
                return vals[0].value;

        }catch(Exception e)
        {
            log.error(e.getMessage(),e);
        }

        return null;
    }


    public static String getFullName(Concept concept) {
        AuthorityMetadataValue[] vals = concept.getMetadata("journal","fullname",null, Item.ANY);
        if(vals != null && vals.length > 0)
            return vals[0].value;

        return null;
    }

    public static String getJournalShortID(Concept concept) {
        AuthorityMetadataValue[] vals = concept.getMetadata("journal","journalID",null, Item.ANY);
        if(vals != null && vals.length > 0)
            return vals[0].value;

        return null;
    }

    public static String getPublicationBlackout(Concept concept) {
        AuthorityMetadataValue[] vals = concept.getMetadata("journal","publicationBlackout",null, Item.ANY);
        if(vals != null && vals.length > 0)
            return vals[0].value;

        return null;
    }

    public static boolean getBooleanPublicationBlackout(Concept concept) {
        AuthorityMetadataValue[] vals = concept.getMetadata("journal","publicationBlackout",null, Item.ANY);
        if(vals != null && vals.length > 0)
            return vals[0].value.toLowerCase().equals("true");

        return false;
    }
    public static String getNotifyOnReview(Concept concept) {
        AuthorityMetadataValue[] vals = concept.getMetadata("journal","notifyOnReview",null, Item.ANY);
        if(vals != null && vals.length > 0)
            return vals[0].value;

        return null;
    }
    public static String[] getListNotifyOnReview(Concept concept) {
        AuthorityMetadataValue[] vals = concept.getMetadata("journal","notifyOnReview",null, Item.ANY);
        if(vals != null && vals.length > 0)
            return vals[0].value.split(",");

        return null;
    }
    public static String getNotifyOnArchive(Concept concept) {
        AuthorityMetadataValue[] vals = concept.getMetadata("journal","notifyOnArchive",null, Item.ANY);
        if(vals != null && vals.length > 0)
            return vals[0].value;

        return null;
    }

    public static String[] getListNotifyOnArchive(Concept concept) {
        AuthorityMetadataValue[] vals = concept.getMetadata("journal","notifyOnArchive",null, Item.ANY);
        if(vals != null && vals.length > 0)
            return vals[0].value.split(",");

        return null;
    }
    public static String getNotifyWeekly(Concept concept) {
        AuthorityMetadataValue[] vals = concept.getMetadata("journal","notifyWeekly",null, Item.ANY);
        if(vals != null && vals.length > 0)
            return vals[0].value;

        return null;
    }
    public static String[] getListNotifyWeekly(Concept concept) {
        AuthorityMetadataValue[] vals = concept.getMetadata("journal","notifyWeekly",null, Item.ANY);
        if(vals != null && vals.length > 0)
            return vals[0].value.split(",");

        return null;
    }
    public static String getParsingScheme(Concept concept) {
        AuthorityMetadataValue[] vals = concept.getMetadata("journal","parsingScheme",null, Item.ANY);
        if(vals != null && vals.length > 0)
            return vals[0].value;

        return null;
    }


    public static String getEmbargoAllowed(Concept concept) {
        AuthorityMetadataValue[] vals = concept.getMetadata("journal","embargoAllowed",null, Item.ANY);
        if(vals != null && vals.length > 0)
            return vals[0].value;

        return null;
    }
    public static boolean getBooleanEmbargoAllowed(Concept concept) {
        AuthorityMetadataValue[] vals = concept.getMetadata("journal","embargoAllowed",null, Item.ANY);
        if(vals != null && vals.length > 0)
            return vals[0].value.toLowerCase().equals("true");

        return false;
    }

    public static String getAllowReviewWorkflow(Concept concept) {
        AuthorityMetadataValue[] vals = concept.getMetadata("journal","allowReviewWorkflow",null, Item.ANY);
        if(vals != null && vals.length > 0)
            return vals[0].value;

        return null;
    }

    public static boolean getBooleanAllowReviewWorkflow(Concept concept) {
        AuthorityMetadataValue[] vals = concept.getMetadata("journal","allowReviewWorkflow",null, Item.ANY);
        if(vals != null && vals.length > 0)
            return vals[0].value.toLowerCase().equals("true");

        return false;
    }

    public static String getSponsorName(Concept concept) {
        AuthorityMetadataValue[] vals = concept.getMetadata("journal","sponsorName",null, Item.ANY);
        if(vals != null && vals.length > 0)
            return vals[0].value;

        return null;
    }

    public static String getDescription(Concept concept) {
        AuthorityMetadataValue[] vals = concept.getMetadata("journal","description",null, Item.ANY);
        if(vals != null && vals.length > 0)
            return vals[0].value;

        return null;
    }

    public static String getMemberName(Concept concept) {
        AuthorityMetadataValue[] vals = concept.getMetadata("journal","memberName",null, Item.ANY);
        if(vals != null && vals.length > 0)
            return vals[0].value;

        return null;
    }

    /**
     * Replaces escaped characters with their original representations
     * @param escaped a filename that has been escaped by
     * @return The original string, after unescaping
     */
    public static String unescapeFilename(String escaped) {
        StringBuilder sb = new StringBuilder();
        int i;
        while ((i = escaped.indexOf("%")) >= 0) {
            sb.append(escaped.substring(0, i));
            sb.append((char) Integer.parseInt(escaped.substring(i + 1, i + 3), 16));
            escaped = escaped.substring(i + 3);
        }
        sb.append(escaped);
        return sb.toString();
    }

    /**
     * Replaces invalid filename characters by percent-escaping.  Based on
     * http://stackoverflow.com/questions/1184176/how-can-i-safely-encode-a-string-in-java-to-use-as-a-filename
     *
     * @param filename A filename to escape
     * @return The filename, with special characters escaped with percent
     */
    public static String escapeFilename(String filename) {
        final char fileSep = System.getProperty("file.separator").charAt(0); // e.g. '/'
        final char escape = '%';
        int len = filename.length();
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            char ch = filename.charAt(i);
            if (ch < ' ' || ch >= 0x7F || ch == fileSep
                    || (ch == '.' && i == 0) // we don't want to collide with "." or ".."!
                    || ch == escape) {
                sb.append(escape);
                if (ch < 0x10) {
                    sb.append('0'); // Leading zero
                }
                sb.append(Integer.toHexString(ch));
            } else {
                sb.append(ch);
            }
        }
        return sb.toString();
    }
    public static String findKeyByFullname(String fullname){
        Map<String, String> props = journalProperties.get(fullname);
        if(props!=null)
            return props.get(JOURNAL_ID);

        return null;
    }


    public static Map<String, String> getPropertiesByJournal(String key){
        return journalProperties.get(key);
    }

    public static RecommendedBlackoutAction recommendedBlackoutAction(Context context, Item item, Collection collection) throws SQLException {
        // get Journal
        Item dataPackage=item;
        if(!isDataPackage(collection))
            dataPackage = DryadWorkflowUtils.getDataPackage(context, item);
        DCValue[] journalFullNames = dataPackage.getMetadata("prism.publicationName");
        String journalFullName=null;
        if(journalFullNames!=null && journalFullNames.length > 0){
            journalFullName=journalFullNames[0].value;
        }

        Map<String, String> values = journalProperties.get(journalFullName);
        // Ignore blackout setting if journal is not (yet) integrated
        // get journal's blackout setting
        // journal is blacked out if its blackout setting is true or if it has no setting
        String isIntegrated = null;
        String isBlackedOut = null;
        if(values!=null && values.size()>0) {
            isIntegrated = values.get(INTEGRATED);
            isBlackedOut = values.get(PUBLICATION_BLACKOUT);
        }

        if(isIntegrated == null || isIntegrated.equals("false")) {
            // journal is not integrated.  Enter blackout by default
            return RecommendedBlackoutAction.JOURNAL_NOT_INTEGRATED;
        } else if(isBlackedOut==null || isBlackedOut.equals("true")) {
            // journal has a blackout setting and it's set to true
            return RecommendedBlackoutAction.BLACKOUT_TRUE;
        } else {
            // journal is integrated but blackout setting is false or missing
            return RecommendedBlackoutAction.BLACKOUT_FALSE;
        }
    }
    private static boolean isDataPackage(Collection coll) throws SQLException {
        return coll.getHandle().equals(ConfigurationManager.getProperty("submit.publications.collection"));
    }

    public static Map<String, String> findJournalProperties(Context c, String journal){
        Map<String, String> myJournalProperties = new HashMap<String, String>();


        try {
            String publicationNameProp = ConfigurationManager.getProperty("solrauthority.searchscheme.prism_publicationName");
            Scheme scheme = Scheme.findByIdentifier(c, publicationNameProp);
            int schemeID = scheme.getID();
            Concept[] concepts = Concept.findByPreferredLabel(c,journal, schemeID);
            log.debug("journal lookup: name = " + journal + ", publicationNameProp = " + publicationNameProp + ", ID  = " + schemeID);
            //todo:add the journal order
            Concept concept = concepts[0];

            String key = concept.getPreferredLabel();
            ArrayList<AuthorityMetadataValue> metadataValues = concept.getMetadata();
            Map<String, String> map = new HashMap<String, String>();
            for(AuthorityMetadataValue metadataValue : metadataValues){

                if(metadataValue.qualifier!=null){
                    myJournalProperties.put(metadataValue.element + '.' + metadataValue.qualifier, metadataValue.value);
                }
                else
                {
                    myJournalProperties.put(metadataValue.element, metadataValue.value);
                }

            }

        }catch (Exception e) {
            log.error("Error while loading journal properties", e);
        }
        return myJournalProperties;

    }

    public static Boolean shouldEnterBlackoutByDefault(Context context, Item item, Collection collection) throws SQLException {
        JournalUtils.RecommendedBlackoutAction action = JournalUtils.recommendedBlackoutAction(context, item, collection);
        return (action == JournalUtils.RecommendedBlackoutAction.BLACKOUT_TRUE ||
                action == JournalUtils.RecommendedBlackoutAction.JOURNAL_NOT_INTEGRATED);
    }

    public static void writeManuscriptToXMLFile(Context context, Manuscript manuscript) {
        try {
            Concept concept = JournalUtils.getJournalConceptById(context, manuscript.manuscriptId);
            String filename = JournalUtils.escapeFilename(manuscript.manuscriptId + ".xml");
            File file = new File(JournalUtils.getMetadataDir(concept), filename);
            FileOutputStream outputStream = null;

            try {
                outputStream = new FileOutputStream(file);
            } catch (FileNotFoundException e) {
                log.warn("couldn't open a file to write", e);
            }

            if (outputStream != null) {
                try {
                    ManuscriptToLegacyXMLConverter.convertToInternalXML(manuscript, outputStream);
                    log.info("wrote xml to file " + file.getAbsolutePath());
                } catch (JAXBException e) {
                    log.warn("couldn't convert to XML");
                }
            }
        } catch (SQLException e) {
            throw new SubmissionException(e);
        }
    }

    public static void writeManuscriptToDB(Context context, Manuscript manuscript) throws StorageException {
        StoragePath storagePath = new StoragePath();
        storagePath.addPathElement(Organization.ORGANIZATION_CODE, manuscript.organization.organizationCode);

        // check to see if this organization exists in the database: if not, add it.
        OrganizationDatabaseStorageImpl organizationStorage = new OrganizationDatabaseStorageImpl();
        List<Organization> orgs = organizationStorage.getResults(storagePath, manuscript.organization.organizationCode, 0);
        if (orgs.size() == 0) {
            try {
                log.info ("creating an organization " + manuscript.organization.organizationCode);
                organizationStorage.create(storagePath, manuscript.organization);
            } catch (StorageException ex) {
                log.error("Exception creating organizations", ex);
            }
        }

        ManuscriptDatabaseStorageImpl manuscriptStorage = new ManuscriptDatabaseStorageImpl();
        storagePath.addPathElement(Manuscript.MANUSCRIPT_ID, manuscript.manuscriptId);
        List<Manuscript> manuscripts = manuscriptStorage.getResults(storagePath, manuscript.manuscriptId, 10);

        // if there isn't a manuscript already in the db, create it. Otherwise, update.
        if (manuscripts.size() == 0) {
            try {
                manuscriptStorage.create(storagePath, manuscript);
                log.info("adding manuscript " + manuscript.manuscriptId + " to the database for organization " + manuscript.organization.organizationCode);
            } catch (StorageException ex) {
                log.error("Exception creating manuscript", ex);
            }
        } else {
            try {
                manuscriptStorage.update(storagePath, manuscript);
                log.info("updating manuscript " + manuscript.manuscriptId + " to the database for organization " + manuscript.organization.organizationCode);
            } catch (StorageException ex) {
                log.error("Exception updating manuscript", ex);
            }
        }
    }

}
