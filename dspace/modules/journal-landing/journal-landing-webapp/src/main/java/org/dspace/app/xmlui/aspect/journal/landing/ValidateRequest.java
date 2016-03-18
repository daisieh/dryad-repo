/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.app.xmlui.aspect.journal.landing;

import org.apache.avalon.framework.parameters.Parameters;
import org.apache.cocoon.acting.AbstractAction;
import org.apache.cocoon.environment.Redirector;
import org.apache.cocoon.environment.SourceResolver;
import org.apache.log4j.Logger;
import org.dspace.app.xmlui.utils.ContextUtil;
import org.dspace.content.authority.Concept;
import org.dspace.core.Context;
import org.datadryad.api.DryadJournalConcept;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

import static org.dspace.app.xmlui.aspect.journal.landing.Const.PARAM_CONCEPT_ID;
import static org.dspace.app.xmlui.aspect.journal.landing.Const.PARAM_JOURNAL_ISSN;
import static org.dspace.content.authority.Concept.findByConceptMetadata;

/**
 * Cocoon Action to confirm that the requested journal landing page is for 
 * a journal that is under authority control in Dryad.
 * 
 * @author Nathan Day
 */
public class ValidateRequest extends AbstractAction {

    private static final Logger log = Logger.getLogger(ValidateRequest.class);
    private static final Pattern issnPattern = Pattern.compile("\\d{4}-\\d{4}");

    @Override
    public Map act(Redirector redirector, SourceResolver resolver, Map objectModel,
                    String source, Parameters parameters) throws Exception
    {
        String journalISSN = parameters.getParameter(PARAM_JOURNAL_ISSN);
        if (journalISSN == null || journalISSN.length() == 0 || !issnPattern.matcher(journalISSN).matches()) {
            return null;
        }
        Context context = ContextUtil.obtainContext(objectModel);
        ArrayList<Concept> journalConcepts = findByConceptMetadata(context, journalISSN, "journal", "issn");
        if (journalConcepts.size() == 0) {
            return null;
        }
        Concept journalConcept = journalConcepts.get(0);
        if (journalConcept != null) {
            Map map = new HashMap();
            map.put(PARAM_CONCEPT_ID, journalConcept.getID());
            return map;
        }
        return null;
    }
}

