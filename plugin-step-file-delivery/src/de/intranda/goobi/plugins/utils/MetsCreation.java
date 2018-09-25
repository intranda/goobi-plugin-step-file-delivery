package de.intranda.goobi.plugins.utils;

/**
 * This file is part of the Goobi Application - a Workflow tool for the support of mass digitization.
 * 
 * Visit the websites for more information.
 *          - http://digiverso.com
 *          - http://www.intranda.com
 * 
 * Copyright 2011, intranda GmbH, Göttingen
 * 
 * 
 * This program is free software; you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free
 * Software Foundation; either version 2 of the License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License along with this program; if not, write to the Free Software Foundation, Inc., 59
 * Temple Place, Suite 330, Boston, MA 02111-1307 USA
 * 
 * Linking this library statically or dynamically with other modules is making a combined work based on this library. Thus, the terms and conditions
 * of the GNU General Public License cover the whole combination. As a special exception, the copyright holders of this library give you permission to
 * link this library with independent modules to produce an executable, regardless of the license terms of these independent modules, and to copy and
 * distribute the resulting executable under terms of your choice, provided that you also meet, for each linked independent module, the terms and
 * conditions of the license of that module. An independent module is a module which is not derived from or based on this library. If you modify this
 * library, you may extend this exception to your version of the library, but you are not obliged to do so. If you do not wish to do so, delete this
 * exception statement from your version.
 */
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

import org.apache.log4j.Logger;
import org.goobi.beans.Process;
import org.goobi.beans.ProjectFileGroup;

import de.sub.goobi.config.ConfigurationHelper;
import de.sub.goobi.helper.Helper;
import de.sub.goobi.helper.VariableReplacer;
import de.sub.goobi.helper.exceptions.DAOException;
import de.sub.goobi.helper.exceptions.ExportFileException;
import de.sub.goobi.helper.exceptions.InvalidImagesException;
import de.sub.goobi.helper.exceptions.SwapException;
import de.sub.goobi.helper.exceptions.UghHelperException;
import de.sub.goobi.metadaten.MetadatenImagesHelper;
import ugh.dl.ContentFile;
import ugh.dl.DigitalDocument;
import ugh.dl.DocStruct;
import ugh.dl.Fileformat;
import ugh.dl.Prefs;
import ugh.dl.VirtualFileGroup;
import ugh.exceptions.DocStructHasNoTypeException;
import ugh.exceptions.MetadataTypeNotAllowedException;
import ugh.exceptions.PreferencesException;
import ugh.exceptions.ReadException;
import ugh.exceptions.TypeNotAllowedForParentException;
import ugh.exceptions.WriteException;
import ugh.fileformats.mets.MetsModsImportExport;

public class MetsCreation {
    protected Helper help = new Helper();
    protected Prefs myPrefs;

    protected static final Logger myLogger = Logger.getLogger(MetsCreation.class);



    /**
     * DMS-Export an eine gewünschte Stelle
     * 
     * @param myProzess
     * @param zielVerzeichnis
     * @throws InterruptedException
     * @throws IOException
     * @throws PreferencesException
     * @throws WriteException
     * @throws UghHelperException
     * @throws ExportFileException
     * @throws MetadataTypeNotAllowedException
     * @throws DocStructHasNoTypeException
     * @throws DAOException
     * @throws SwapException
     * @throws ReadException
     * @throws TypeNotAllowedForParentException
     */
    public boolean startExport(Process myProzess, String inZielVerzeichnis) throws IOException, InterruptedException, PreferencesException,
    WriteException, DocStructHasNoTypeException, MetadataTypeNotAllowedException, ExportFileException, UghHelperException, ReadException,
    SwapException, DAOException, TypeNotAllowedForParentException {

        /*
         * -------------------------------- Read Document --------------------------------
         */
        this.myPrefs = myProzess.getRegelsatz().getPreferences();
        String atsPpnBand = myProzess.getTitel();
        Fileformat gdzfile = myProzess.readMetadataFile();

        //        String zielVerzeichnis = prepareUserDirectory(inZielVerzeichnis);

        String targetFileName = inZielVerzeichnis + atsPpnBand + "_mets.xml";
        return writeMetsFile(myProzess, targetFileName, gdzfile, false);

    }



    /**
     * write MetsFile to given Path
     * 
     * @param myProzess the Process to use
     * @param targetFileName the filename where the metsfile should be written
     * @param gdzfile the FileFormat-Object to use for Mets-Writing
     * @throws DAOException
     * @throws SwapException
     * @throws InterruptedException
     * @throws IOException
     * @throws TypeNotAllowedForParentException
     */

    public boolean writeMetsFile(Process myProzess, String targetFileName, Fileformat gdzfile, boolean writeLocalFilegroup)
            throws PreferencesException, WriteException, IOException, InterruptedException, SwapException, DAOException,
            TypeNotAllowedForParentException {
        myPrefs = myProzess.getRegelsatz().getPreferences();
        MetsModsImportExport mm = new MetsModsImportExport(myPrefs);
        mm.setWriteLocal(writeLocalFilegroup);
        String imageFolderPath = myProzess.getImagesDirectory();
        File imageFolder = new File(imageFolderPath);
        /*
         * before creating mets file, change relative path to absolute -
         */
        DigitalDocument dd = gdzfile.getDigitalDocument();
        if (dd.getFileSet() == null) {
            Helper.setMeldung(myProzess.getTitel() + ": digital document does not contain images; temporarily adding them for mets file creation");

            MetadatenImagesHelper mih = new MetadatenImagesHelper(this.myPrefs, dd);
            mih.createPagination(myProzess, null);
        }

        /*
         * get the topstruct element of the digital document depending on anchor property
         */
        DocStruct topElement = dd.getLogicalDocStruct();
        if (this.myPrefs.getDocStrctTypeByName(topElement.getType().getName()).isAnchor()) {
            if (topElement.getAllChildren() == null || topElement.getAllChildren().size() == 0) {
                throw new PreferencesException(myProzess.getTitel()
                        + ": the topstruct element is marked as anchor, but does not have any children for physical docstrucs");
            } else {
                topElement = topElement.getAllChildren().get(0);
            }
        }

        /*
         * -------------------------------- if the top element does not have any image related, set them all --------------------------------
         */
        if (topElement.getAllToReferences("logical_physical") == null || topElement.getAllToReferences("logical_physical").size() == 0) {
            if (dd.getPhysicalDocStruct() != null && dd.getPhysicalDocStruct().getAllChildren() != null) {
                Helper.setMeldung(myProzess.getTitel()
                        + ": topstruct element does not have any referenced images yet; temporarily adding them for mets file creation");
                for (DocStruct mySeitenDocStruct : dd.getPhysicalDocStruct().getAllChildren()) {
                    topElement.addReferenceTo(mySeitenDocStruct, "logical_physical");
                }
            } else {
                Helper.setFehlerMeldung(myProzess.getTitel() + ": could not found any referenced images, export aborted");
                dd = null;
                return false;
            }
        }
        // if (dd == null) {
        // return false;
        // } else {
        for (ContentFile cf : dd.getFileSet().getAllFiles()) {
            String location = cf.getLocation();
            // If the file's location string shoes no sign of any protocol,
            // use the file protocol.
            if (!location.contains("://")) {
                location = "file://" + location;
            }
            URL url = new URL(location);
            File f = new File(imageFolder, url.getFile());
            cf.setLocation(f.toURI().toString());
        }

        mm.setDigitalDocument(dd);

        /*
         * -------------------------------- wenn Filegroups definiert wurden, werden diese jetzt in die Metsstruktur übernommen
         * --------------------------------
         */
        // Replace all pathes with the given VariableReplacer, also the file
        // group pathes!
        VariableReplacer vp = new VariableReplacer(mm.getDigitalDocument(), this.myPrefs, myProzess, null);
        List<ProjectFileGroup> myFilegroups = myProzess.getProjekt().getFilegroups();

        if (myFilegroups != null && myFilegroups.size() > 0) {
            for (ProjectFileGroup pfg : myFilegroups) {
                // check if source files exists
                if (pfg.getFolder() != null && pfg.getFolder().length() > 0) {
                    File folder = new File(myProzess.getMethodFromName(pfg.getFolder()));
                    if (folder != null && folder.exists() && folder.list().length > 0) {
                        VirtualFileGroup v = new VirtualFileGroup();
                        v.setName(pfg.getName());
                        v.setPathToFiles(vp.replace(pfg.getPath()));
                        v.setMimetype(pfg.getMimetype());
                        v.setFileSuffix(pfg.getSuffix());
                        mm.getDigitalDocument().getFileSet().addVirtualFileGroup(v);
                    }
                } else {
                    VirtualFileGroup v = new VirtualFileGroup();
                    v.setName(pfg.getName());
                    v.setPathToFiles(vp.replace(pfg.getPath()));
                    v.setMimetype(pfg.getMimetype());
                    v.setFileSuffix(pfg.getSuffix());
                    mm.getDigitalDocument().getFileSet().addVirtualFileGroup(v);
                }
            }
        }

        // Replace rights and digiprov entries.
        mm.setRightsOwner(vp.replace(myProzess.getProjekt().getMetsRightsOwner()));
        mm.setRightsOwnerLogo(vp.replace(myProzess.getProjekt().getMetsRightsOwnerLogo()));
        mm.setRightsOwnerSiteURL(vp.replace(myProzess.getProjekt().getMetsRightsOwnerSite()));
        mm.setRightsOwnerContact(vp.replace(myProzess.getProjekt().getMetsRightsOwnerMail()));
        mm.setDigiprovPresentation(vp.replace(myProzess.getProjekt().getMetsDigiprovPresentation()));
        mm.setDigiprovReference(vp.replace(myProzess.getProjekt().getMetsDigiprovReference()));
        mm.setDigiprovPresentationAnchor(vp.replace(myProzess.getProjekt().getMetsDigiprovPresentationAnchor()));
        mm.setDigiprovReferenceAnchor(vp.replace(myProzess.getProjekt().getMetsDigiprovReferenceAnchor()));

        mm.setPurlUrl(vp.replace(myProzess.getProjekt().getMetsPurl()));
        mm.setContentIDs(vp.replace(myProzess.getProjekt().getMetsContentIDs()));

        String pointer = myProzess.getProjekt().getMetsPointerPath();
        pointer = vp.replace(pointer);
        mm.setMptrUrl(pointer);

        String anchor = myProzess.getProjekt().getMetsPointerPathAnchor();
        pointer = vp.replace(anchor);
        mm.setMptrAnchorUrl(pointer);

        // if (!ConfigMain.getParameter("ImagePrefix", "\\d{8}").equals("\\d{8}")) {
        List<String> images = new ArrayList<String>();
        if (ConfigurationHelper.getInstance().isExportValidateImages()) {
            try {
                // TODO andere Dateigruppen nicht mit image Namen ersetzen
                images = new MetadatenImagesHelper(this.myPrefs, dd).getDataFiles(myProzess, null);
                int sizeOfPagination = dd.getPhysicalDocStruct().getAllChildren().size();
                if (images != null) {
                    int sizeOfImages = images.size();
                    if (sizeOfPagination == sizeOfImages) {
                        dd.overrideContentFiles(images);
                    } else {
                        List<String> param = new ArrayList<String>();
                        param.add(String.valueOf(sizeOfPagination));
                        param.add(String.valueOf(sizeOfImages));
                        Helper.setFehlerMeldung(Helper.getTranslation("imagePaginationError", param));
                        return false;
                    }
                }
            } catch (IndexOutOfBoundsException e) {
                myLogger.error(e);
                return false;
            } catch (InvalidImagesException e) {
                myLogger.error(e);
                return false;
            }
        } else {
            // create pagination out of virtual file names
            dd.addAllContentFiles();

        }
        mm.write(targetFileName);
        Helper.setMeldung(null, myProzess.getTitel() + ": ", "Export finished");
        return true;
        // }
    }

    // private static String getMimetype(String filename) {
    // FileNameMap fnm = URLConnection.getFileNameMap();
    // return fnm.getContentTypeFor(filename);
    // }
}
