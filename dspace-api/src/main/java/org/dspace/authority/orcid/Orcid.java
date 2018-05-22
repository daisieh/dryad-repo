/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.authority.orcid;

import org.apache.oltu.oauth2.client.OAuthClient;
import org.apache.oltu.oauth2.client.URLConnectionClient;
import org.apache.oltu.oauth2.client.request.OAuthClientRequest;
import org.apache.oltu.oauth2.client.response.OAuthJSONAccessTokenResponse;
import org.apache.oltu.oauth2.common.message.types.GrantType;
import org.dspace.authority.AuthorityValue;
import org.dspace.authority.orcid.model.Bio;
import org.dspace.authority.orcid.model.Work;
import org.dspace.authority.orcid.xml.XMLtoBio;
import org.dspace.authority.orcid.xml.XMLtoWork;
import org.dspace.authority.rest.RestSource;
import org.apache.log4j.Logger;
import org.dspace.core.ConfigurationManager;
import org.dspace.utils.DSpace;
import org.w3c.dom.Document;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;

/**
 *
 * @author Antoine Snyers (antoine at atmire.com)
 * @author Kevin Van de Velde (kevin at atmire dot com)
 * @author Ben Bosman (ben at atmire dot com)
 * @author Mark Diggory (markd at atmire dot com)
 */
public class Orcid extends RestSource {

    /**
     * log4j logger
     */
    private static Logger log = Logger.getLogger(Orcid.class);

    private static Orcid orcid;

    private static String token = null;

    static {
        authenticate();
    }

    private static void authenticate() {
        if (token == null) {
            try {
                OAuthClientRequest oAuthClientRequest = OAuthClientRequest
                        .tokenLocation(ConfigurationManager.getProperty("authentication-oauth", "application-token-url"))
                        .setGrantType(GrantType.CLIENT_CREDENTIALS)
                        .setClientId(ConfigurationManager.getProperty("authentication-oauth", "application-client-id"))
                        .setClientSecret(ConfigurationManager.getProperty("authentication-oauth", "application-client-secret"))
                        .setRedirectURI(ConfigurationManager.getProperty("authentication-oauth", "application-redirect-uri"))
                        .setScope("/read-public")
                        .buildQueryMessage();

                //create OAuth client that uses custom http client under the hood
                OAuthClient oAuthClient = new OAuthClient(new URLConnectionClient());

                OAuthJSONAccessTokenResponse oAuthResponse = oAuthClient.accessToken(oAuthClientRequest, OAuthJSONAccessTokenResponse.class);
                token = oAuthResponse.getAccessToken();
                log.error("token is " + token);
            } catch (Exception e) {
                log.error("couldn't authenticate orcid: " + e.getMessage());
            }
        }
    }

    public static String getToken() {
        if (token == null) {
            authenticate();
        }
        return token;
    }

    public static Orcid getOrcid() {
        if (orcid == null) {
            orcid = new DSpace().getServiceManager().getServiceByName("OrcidSource", Orcid.class);
        }
        return orcid;
    }

    public Orcid(String url) {
        super(url);
    }

    public static Bio getBio(String id) {
        return getBio(id, getToken());
    }

    /**
     * Member URI Get Bio Support for Retrieving "limited" details.
     *
     * @param id
     * @param token
     * @return
     */
    public static Bio getBio(String id, String token) {
       // https://api.sandbox.orcid.org?access_token=d50eb967-555f-4671-9f35-8b413509b7f1
        Document bioDocument = restConnector.get(id  + "/person", token);
        XMLtoBio converter = new XMLtoBio();
        Bio bio = converter.convert(bioDocument);
        bio.setOrcid(id);
        return bio;
    }

    public List<Work> getWorks(String id) {
        Document document = restConnector.get(id + "/orcid-works");
        XMLtoWork converter = new XMLtoWork();
        return converter.convert(document);
    }

    public List<Bio> queryBio(String name, int start, int rows) {
        Document bioDocument = restConnector.get("search/?q=" + URLEncoder.encode(name) + "&start=" + start + "&rows=" + rows);
        XMLtoBio converter = new XMLtoBio();
        return converter.convertList(bioDocument);
    }

    @Override
    public List<AuthorityValue> queryAuthorities(String text, int max) {
        List<Bio> bios = queryBio(text, 0, max);
        List<AuthorityValue> authorities = new ArrayList<AuthorityValue>();
        for (Bio bio : bios) {
            authorities.add(OrcidAuthorityValue.create(bio));
        }
        return authorities;
    }

    @Override
    public AuthorityValue queryAuthorityID(String id) {
        Bio bio = getBio(id);
        return OrcidAuthorityValue.create(bio);
    }
}
