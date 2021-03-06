/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */

package org.dspace.xoai.solr;

import java.net.MalformedURLException;

import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;
import org.apache.solr.client.solrj.SolrServer;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.impl.CommonsHttpSolrServer;
import org.dspace.core.ConfigurationManager;

/**
 * 
 * @author Lyncode Development Team <dspace@lyncode.com>
 */
public class DSpaceSolrServer
{
    private static Logger log = LogManager.getLogger(DSpaceSolrServer.class);

    private static SolrServer _server = null;

    // setter for solr server for mocking during tests
    public static void setServer(SolrServer server)
    {
        _server = server;
    }

    public static SolrServer getServer() throws SolrServerException
    {
        if (_server == null)
        {
            try
            {
                _server = new CommonsHttpSolrServer(
                        ConfigurationManager.getProperty("xoai", "solr.url"));
                log.debug("Solr Server Initialized");
            }
            catch (MalformedURLException e)
            {
                throw new SolrServerException(e);
            }
            catch (Exception e)
            {
                log.error(e.getMessage(), e);
            }
        }
        return _server;
    }
}
