/*
 * Licensed under the European Union Public Licence (EUPL) V.1.1 
 */
package com.csc.fi.ioapi.utils;

import com.hp.hpl.jena.rdf.model.Model;
import com.hp.hpl.jena.rdf.model.ModelFactory;
import com.hp.hpl.jena.rdf.model.RDFReader;
import com.hp.hpl.jena.rdf.model.Resource;
import com.hp.hpl.jena.rdf.model.ResourceFactory;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.util.logging.Logger;
import org.apache.jena.atlas.web.ContentType;
import org.apache.jena.atlas.web.HttpException;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFLanguages;

/**
 *
 * @author malonen
 */
public class NamespaceResolver {
   
    static final private Logger logger = Logger.getLogger(NamespaceResolver.class.getName());
    	
	public static Boolean resolveNamespace(String namespace) {
		
            try { // Unexpected exception
                    
		if (NamespaceManager.isSchemaInStore(namespace)) {
			logger.info("Schema found in store: "+namespace);
			return true;
		} else {
			logger.info("Trying to connect to: "+namespace);
                        Model model = ModelFactory.createDefaultModel();

                        URL url;
             
                        try {
                            url = new URL(namespace);
                        } catch (MalformedURLException e) {
                            logger.warning("Malformed URL: "+namespace);
                            e.printStackTrace();
                            return false;
                        }
                        
                        if(!("https".equals(url.getProtocol()) || "http".equals(url.getProtocol()))) {
                            logger.warning("Namespace NOT http or https: "+namespace);
                            return false;
                        }
                                
			HttpURLConnection connection = null;
                        
			try { // IOException

				connection = (HttpURLConnection) url.openConnection();				
				// 5 seconds
				connection.setConnectTimeout(8000);
				// 5 minutes
				connection.setReadTimeout(60000);
				connection.setInstanceFollowRedirects(true);
                                //,text/rdf+n3,application/turtle,application/rdf+n3
				//"application/rdf+xml,application/xml,text/html");
				connection.setRequestProperty("Accept","application/rdf+xml,application/turtle;q=0.8,application/x-turtle;q=0.8,text/turtle;q=0.8,text/rdf+n3;q=0.5,application/n3;q=0.5,text/n3;q=0.5");
				
                                try { // SocketTimeOut
					
                                    connection.connect();

                                    InputStream stream;

                                    try {
                                        stream = connection.getInputStream();
                                    } catch (FileNotFoundException e) {
                                            logger.warning("Couldnt open stream from "+namespace);
                                            e.printStackTrace();
                                            return false;
                                    } 

                                    logger.info("Opened connection");
                                    logger.info(connection.getURL().toString());
                                    logger.info(connection.getContentType());

                                    if(connection.getContentType()==null) {
                                            logger.info("Couldnt resolve Content-Type from: "+namespace);
                                            return false;
                                    }

                                    String contentType = connection.getContentType();

                                    if(contentType==null){
                                            logger.info("ContentType is null");
                                            stream.close();
                                            connection.disconnect();
                                            return false;
                                    }

                                    ContentType guess = ContentType.create(contentType);
                                    Lang testLang = RDFLanguages.contentTypeToLang(guess);

                                    if(testLang!=null) {

                                        logger.info("Trying to parse "+testLang.getName()+" from "+namespace);

                                        RDFReader reader = model.getReader(testLang.getName());

                                        reader.setProperty("error-mode", "lax");
                                        reader.read(model, connection.getInputStream(), namespace);

                                        stream.close();
                                        connection.disconnect();

                                    } else {
                                        logger.info("Cound not resolve Content-Type "+contentType+" from "+namespace);
                                        stream.close();
                                        connection.disconnect();
                                        return false;
                                    }
					
					
                            } catch (SocketTimeoutException e) {
                                logger.info("Timeout from "+namespace);
                                e.printStackTrace();
                                return false;
                            }
				
			} catch (IOException e) {
                            logger.info("Could not read file from "+namespace);
                            e.printStackTrace();
                            return false;
			}  
				
                        logger.info("Model-size is: "+model.size());

                        try {
                                if(model.size()>1) {
                                        NamespaceManager.putSchemaToStore(namespace,model);
                                } else {
                                    logger.warning("Namespace contains empty schema: "+namespace);
                                    return false;
                                }
 
                                return true;
                                
                        } catch(HttpException ex) {
                                logger.warning("Error in saving the model loaded from "+namespace);
                                ex.printStackTrace();
                                return false;
                        }


			} 
                
		} catch(Exception ex) {
			logger.warning("Error in loading the "+namespace);
			ex.printStackTrace();
			return false;
		}
		
		
	}
    
    
}