package org.datadryad.authority;

import org.datadryad.api.DryadJournalConcept;
import org.dspace.authority.AuthorityValue;
import org.dspace.authority.indexer.AuthorityIndexerInterface;
import org.dspace.content.*;
import org.dspace.core.Context;
import java.util.*;
import org.dspace.JournalUtils;

/**
 * Created by IntelliJ IDEA.
 * User: fabio.bolognesi
 * Date: Mar 1, 2011
 * Time: 2:42:07 PM
 * To change this template use File | Settings | File Templates.
 */
public class JournalConceptIndexer implements AuthorityIndexerInterface {

    private String SOURCE="JOURNALCONCEPTS";
    private AuthorityValue nextValue;

    LinkedList<AuthorityValue> authorities = new LinkedList();


    public static final String FIELD_NAME = "prism_publicationName";

    public void init() {
        DryadJournalConcept[] dryadJournalConcepts = JournalUtils.getAllJournalConcepts();
        for (DryadJournalConcept concept : dryadJournalConcepts) {
            if (concept.isAccepted()) {
                AuthorityValue doc = createHashMap(concept);
                authorities.add(doc);
            }
        }
    }

    @Override
    public void init(Context context, Item item) {
        init();
        //To change body of implemented methods use File | Settings | File Templates.
    }

    @Override
    public void init(Context context) {
        init();
        //To change body of implemented methods use File | Settings | File Templates.
    }

    @Override
    public AuthorityValue nextValue() {
        return nextValue;
    }

    @Override
    public boolean hasMore() {
        nextValue = authorities.poll();
        return nextValue != null;
    }

    @Override
    public void close() {
        //To change body of implemented methods use File | Settings | File Templates.
    }

    private AuthorityValue createHashMap(DryadJournalConcept concept){
        AuthorityValue authorityValue = new AuthorityValue();

        authorityValue.setId(String.valueOf(concept.getConceptID()));
        authorityValue.setSource(SOURCE);
        authorityValue.setField(FIELD_NAME);
        authorityValue.setValue(concept.getFullName());
        // full-text field is for searching, so index it with no spaces.
        authorityValue.setFullText(concept.getFullName().replaceAll("\\s", ""));
        authorityValue.setCreationDate(new Date());
        authorityValue.setLastModified(new Date());

        return authorityValue;
    }

    public String indexerName() {
        return this.getClass().getName();
    }

    public String getSource() {
        return SOURCE;
    }
}


