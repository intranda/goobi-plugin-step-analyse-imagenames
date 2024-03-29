package de.intranda.goobi.plugins;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.configuration.HierarchicalConfiguration;
import org.apache.commons.configuration.XMLConfiguration;
import org.apache.commons.configuration.tree.xpath.XPathExpressionEngine;
import org.apache.commons.lang.SystemUtils;
import org.goobi.beans.Process;
import org.goobi.beans.Step;
import org.goobi.production.enums.LogType;
import org.goobi.production.enums.PluginGuiType;
import org.goobi.production.enums.PluginReturnValue;
import org.goobi.production.enums.PluginType;
import org.goobi.production.enums.StepReturnValue;
import org.goobi.production.plugin.interfaces.IStepPluginVersion2;

import de.sub.goobi.config.ConfigPlugins;
import de.sub.goobi.helper.Helper;
import de.sub.goobi.helper.StorageProvider;
import de.sub.goobi.helper.exceptions.DAOException;
import de.sub.goobi.helper.exceptions.SwapException;
import lombok.Data;
import lombok.extern.log4j.Log4j;
import net.xeoh.plugins.base.annotations.PluginImplementation;
import ugh.dl.ContentFile;
import ugh.dl.DigitalDocument;
import ugh.dl.DocStruct;
import ugh.dl.DocStructType;
import ugh.dl.Fileformat;
import ugh.dl.Metadata;
import ugh.dl.MetadataType;
import ugh.dl.Prefs;
import ugh.dl.Reference;
import ugh.exceptions.DocStructHasNoTypeException;
import ugh.exceptions.MetadataTypeNotAllowedException;
import ugh.exceptions.PreferencesException;
import ugh.exceptions.ReadException;
import ugh.exceptions.TypeNotAllowedAsChildException;
import ugh.exceptions.TypeNotAllowedForParentException;
import ugh.exceptions.WriteException;

@Data
@Log4j
@PluginImplementation
public class ImageNameAnalyzer implements IStepPluginVersion2 {

    private PluginGuiType pluginGuiType = PluginGuiType.NONE;

    private String title = "intranda_step_imagename_analyse";

    private PluginType type = PluginType.Step;

    private Step step;

    private Pattern imagePattern;

    private boolean skipWhenDataExists;

    private Map<String, String> docstructMap;

    private boolean orderImagesByDocstruct = false;

    public ImageNameAnalyzer() {

        XMLConfiguration config = ConfigPlugins.getPluginConfig(title);
        config.setExpressionEngine(new XPathExpressionEngine());
        String regexp = config.getString("/paginationRegex");
        skipWhenDataExists = config.getBoolean("/skipWhenDataExists", false);
        imagePattern = Pattern.compile(regexp);

        docstructMap = new HashMap<>();
        List<HierarchicalConfiguration> itemList = config.configurationsAt("/structureList/item");
        for (HierarchicalConfiguration item : itemList) {
            docstructMap.put(item.getString("@filepart"), item.getString("@docstruct"));
        }
        orderImagesByDocstruct = config.getBoolean("/orderImagesByDocstruct", false);
    }

    @Override
    public PluginReturnValue run() {

        Process process = step.getProzess();
        Prefs prefs = process.getRegelsatz().getPreferences();
        DocStruct physical = null;
        DocStruct logical = null;
        List<String> orderedImageNameList = null;
        Fileformat ff = null;
        DigitalDocument digDoc = null;

        String foldername = null;
        // read image names
        try {
            foldername = process.getImagesOrigDirectory(false);
            orderedImageNameList = StorageProvider.getInstance().list(foldername);
            if (orderedImageNameList.isEmpty()) {
                // abort
                log.info(process.getTitel() + ": no images found");
                return PluginReturnValue.ERROR;
            }
        } catch (IOException | SwapException | DAOException e) {
            log.error(e);
            return PluginReturnValue.ERROR;
        }

        try {
            // read mets file
            ff = process.readMetadataFile();
            digDoc = ff.getDigitalDocument();
            logical = digDoc.getLogicalDocStruct();
            if (logical.getType().isAnchor()) {
                logical = logical.getAllChildren().get(0);
            }
            physical = digDoc.getPhysicalDocStruct();
            // check if pagination was already written
            List<DocStruct> pages = physical.getAllChildren();
            if (pages != null && !pages.isEmpty()) {
                if (skipWhenDataExists) {
                    return PluginReturnValue.FINISH;
                }

                // process contains data, clear it
                for (DocStruct page : pages) {
                    ff.getDigitalDocument().getFileSet().removeFile(page.getAllContentFiles().get(0));
                    List<Reference> refs = new ArrayList<>(page.getAllFromReferences());
                    for (ugh.dl.Reference ref : refs) {
                        ref.getSource().removeReferenceTo(page);
                    }
                }
                while (physical.getAllChildren() != null && !physical.getAllChildren().isEmpty()) {
                    physical.removeChild(physical.getAllChildren().get(0));
                }
                while (logical.getAllChildren() != null && !logical.getAllChildren().isEmpty()) {
                    logical.removeChild(logical.getAllChildren().get(0));
                }
            }

        } catch (ReadException | PreferencesException | IOException | SwapException e) {
            log.error(e);
            return PluginReturnValue.ERROR;
        }

        DocStructType pageType = prefs.getDocStrctTypeByName("page");
        MetadataType physType = prefs.getMetadataTypeByName("physPageNumber");
        MetadataType logType = prefs.getMetadataTypeByName("logicalPageNumber");
        Map<String, DocStruct> docstructs = new HashMap<>();
        DocStruct text = null;
        if (orderImagesByDocstruct) {
            try {
                text = digDoc.createDocStruct(prefs.getDocStrctTypeByName("Textblock"));
            } catch (TypeNotAllowedForParentException e1) {
                log.error(e1);
                return PluginReturnValue.ERROR;
            }
        }
        for (int index = 0; index < orderedImageNameList.size(); index++) {
            String imageName = orderedImageNameList.get(index);
            try {
                DocStruct dsPage = digDoc.createDocStruct(pageType);

                ContentFile cf = new ContentFile();
                if (SystemUtils.IS_OS_WINDOWS) {
                    cf.setLocation("file:/" + foldername + imageName);
                } else {
                    cf.setLocation("file://" + foldername + imageName);
                }
                dsPage.addContentFile(cf);

                physical.addChild(dsPage);
                Metadata mdLogicalPageNo = new Metadata(logType);
                dsPage.addMetadata(mdLogicalPageNo);
                if (!orderImagesByDocstruct) {
                    Metadata mdPhysPageNo = new Metadata(physType);
                    mdPhysPageNo.setValue(String.valueOf(index + 1));
                    dsPage.addMetadata(mdPhysPageNo);
                    logical.addReferenceTo(dsPage, "logical_physical");
                }

                // compare image name against regular expression
                Matcher matcher = imagePattern.matcher(imageName);
                if (matcher.matches()) {
                    String pagination = matcher.group(1);
                    mdLogicalPageNo.setValue(pagination);
                    if (orderImagesByDocstruct) {
                        text.addReferenceTo(dsPage, "logical_physical");
                    }

                } else {
                    mdLogicalPageNo.setValue("uncounted");
                    // compare image name against list of known abbreviations
                    boolean match = false;
                    for (String filepart : docstructMap.keySet()) {
                        Pattern p = Pattern.compile(".*_" + filepart + "(\\d?)[rv]?\\.\\w+");
                        Matcher m = p.matcher(imageName);
                        if (m.matches()) {
                            if (m.groupCount() > 0) {
                                mdLogicalPageNo.setValue(filepart);
                                String groupNumber = m.group(1);
                                if (docstructs.containsKey(filepart + groupNumber)) {
                                    DocStruct ds = docstructs.get(filepart + groupNumber);
                                    ds.addReferenceTo(dsPage, "logical_physical");
                                } else {
                                    DocStructType type = prefs.getDocStrctTypeByName(docstructMap.get(filepart));
                                    DocStruct ds = digDoc.createDocStruct(type);
                                    if (!orderImagesByDocstruct) {
                                        logical.addChild(ds);
                                    }
                                    ds.addReferenceTo(dsPage, "logical_physical");
                                    docstructs.put(filepart + groupNumber, ds);
                                }
                            } else {
                                DocStructType type = prefs.getDocStrctTypeByName(docstructMap.get(filepart));

                                DocStruct ds = digDoc.createDocStruct(type);
                                if (!orderImagesByDocstruct) {
                                    logical.addChild(ds);
                                }
                                ds.addReferenceTo(dsPage, "logical_physical");
                                docstructs.put(filepart, ds);
                            }
                            match = true;
                            break;
                        }

                    }

                    if (!match) {
                        // no match found, use uncounted, add to process log
                        Helper.addMessageToProcessJournal(process.getId(), LogType.ERROR, "no match found for image " + imageName, "Image analyzer");

                        log.debug(process.getTitel() + ": no match found for image " + imageName);
                        if (orderImagesByDocstruct) {
                            text.addReferenceTo(dsPage, "logical_physical");
                        }
                    }
                }

            } catch (TypeNotAllowedForParentException | TypeNotAllowedAsChildException | MetadataTypeNotAllowedException
                    | DocStructHasNoTypeException e) {
                log.error(e);
                return PluginReturnValue.ERROR;
            }
        }
        if (orderImagesByDocstruct) {
            int index = 1;
            try {
                // order docstructs
                if (docstructs.containsKey("VD")) {
                    DocStruct ds = docstructs.get("VD");
                    index = setDocstructAndPagesToLogical(logical, physType, index, ds);
                }
                if (docstructs.containsKey("VDS")) {
                    DocStruct ds = docstructs.get("VDS");
                    index = setDocstructAndPagesToLogical(logical, physType, index, ds);
                }
                if (docstructs.containsKey("VDS2")) {
                    DocStruct ds = docstructs.get("VDS2");
                    index = setDocstructAndPagesToLogical(logical, physType, index, ds);
                }
                if (docstructs.containsKey("VS")) {
                    DocStruct ds = docstructs.get("VS");
                    index = setDocstructAndPagesToLogical(logical, physType, index, ds);
                }
                if (docstructs.containsKey("VS1")) {
                    DocStruct ds = docstructs.get("VS1");
                    index = setDocstructAndPagesToLogical(logical, physType, index, ds);
                }
                if (docstructs.containsKey("VS2")) {
                    DocStruct ds = docstructs.get("VS2");
                    index = setDocstructAndPagesToLogical(logical, physType, index, ds);
                }
                if (docstructs.containsKey("VS3")) {
                    DocStruct ds = docstructs.get("VS3");
                    index = setDocstructAndPagesToLogical(logical, physType, index, ds);
                }
                if (docstructs.containsKey("VS4")) {
                    DocStruct ds = docstructs.get("VS4");
                    index = setDocstructAndPagesToLogical(logical, physType, index, ds);
                }
                if (docstructs.containsKey("VS5")) {
                    DocStruct ds = docstructs.get("VS5");
                    index = setDocstructAndPagesToLogical(logical, physType, index, ds);
                }
                if (docstructs.containsKey("VS6")) {
                    DocStruct ds = docstructs.get("VS6");
                    index = setDocstructAndPagesToLogical(logical, physType, index, ds);
                }
                if (docstructs.containsKey("VS7")) {
                    DocStruct ds = docstructs.get("VS7");
                    index = setDocstructAndPagesToLogical(logical, physType, index, ds);
                }
                if (docstructs.containsKey("VS8")) {
                    DocStruct ds = docstructs.get("VS8");
                    index = setDocstructAndPagesToLogical(logical, physType, index, ds);
                }
                if (docstructs.containsKey("VS9")) {
                    DocStruct ds = docstructs.get("VS9");
                    index = setDocstructAndPagesToLogical(logical, physType, index, ds);
                }
                if (docstructs.containsKey("VS10")) {
                    DocStruct ds = docstructs.get("VS10");
                    index = setDocstructAndPagesToLogical(logical, physType, index, ds);
                }
                if (docstructs.containsKey("VS11")) {
                    DocStruct ds = docstructs.get("VS11");
                    index = setDocstructAndPagesToLogical(logical, physType, index, ds);
                }
                index = setDocstructAndPagesToLogical(logical, physType, index, text);

                if (docstructs.containsKey("NS")) {
                    DocStruct ds = docstructs.get("NS");
                    index = setDocstructAndPagesToLogical(logical, physType, index, ds);
                }
                if (docstructs.containsKey("NS1")) {
                    DocStruct ds = docstructs.get("NS1");
                    index = setDocstructAndPagesToLogical(logical, physType, index, ds);
                }
                if (docstructs.containsKey("NS2")) {
                    DocStruct ds = docstructs.get("NS2");
                    index = setDocstructAndPagesToLogical(logical, physType, index, ds);
                }
                if (docstructs.containsKey("NS3")) {
                    logical.addChild(docstructs.get("NS3"));
                    DocStruct ds = docstructs.get("NS3");
                    index = setDocstructAndPagesToLogical(logical, physType, index, ds);
                }
                if (docstructs.containsKey("NS4")) {
                    DocStruct ds = docstructs.get("NS4");
                    index = setDocstructAndPagesToLogical(logical, physType, index, ds);
                }
                if (docstructs.containsKey("NS5")) {
                    DocStruct ds = docstructs.get("NS5");
                    index = setDocstructAndPagesToLogical(logical, physType, index, ds);
                }
                if (docstructs.containsKey("NS6")) {
                    DocStruct ds = docstructs.get("NS6");
                    index = setDocstructAndPagesToLogical(logical, physType, index, ds);
                }
                if (docstructs.containsKey("NS7")) {
                    DocStruct ds = docstructs.get("NS7");
                    index = setDocstructAndPagesToLogical(logical, physType, index, ds);
                }
                if (docstructs.containsKey("NS8")) {
                    DocStruct ds = docstructs.get("NS8");
                    index = setDocstructAndPagesToLogical(logical, physType, index, ds);
                }
                if (docstructs.containsKey("NS9")) {
                    DocStruct ds = docstructs.get("NS9");
                    index = setDocstructAndPagesToLogical(logical, physType, index, ds);
                }
                if (docstructs.containsKey("HDS")) {
                    DocStruct ds = docstructs.get("HDS");
                    index = setDocstructAndPagesToLogical(logical, physType, index, ds);
                }

                if (docstructs.containsKey("HD")) {
                    DocStruct ds = docstructs.get("HD");
                    index = setDocstructAndPagesToLogical(logical, physType, index, ds);
                }
                if (docstructs.containsKey("ER")) {
                    DocStruct ds = docstructs.get("ER");
                    index = setDocstructAndPagesToLogical(logical, physType, index, ds);
                }
                if (docstructs.containsKey("SV")) {
                    DocStruct ds = docstructs.get("SV");
                    index = setDocstructAndPagesToLogical(logical, physType, index, ds);
                }
                if (docstructs.containsKey("SO")) {
                    DocStruct ds = docstructs.get("SO");
                    index = setDocstructAndPagesToLogical(logical, physType, index, ds);
                }
                if (docstructs.containsKey("SU")) {
                    DocStruct ds = docstructs.get("SU");
                    index = setDocstructAndPagesToLogical(logical, physType, index, ds);
                }
                if (docstructs.containsKey("Farbkarte")) {
                    DocStruct ds = docstructs.get("Farbkarte");
                    index = setDocstructAndPagesToLogical(logical, physType, index, ds);
                }
                if (docstructs.containsKey("Farbkarte_Buchblock")) {
                    DocStruct ds = docstructs.get("Farbkarte_Buchblock");
                    index = setDocstructAndPagesToLogical(logical, physType, index, ds);
                }
                if (docstructs.containsKey("Farbkarte_Bucheinband")) {
                    DocStruct ds = docstructs.get("Farbkarte_Bucheinband");
                    index = setDocstructAndPagesToLogical(logical, physType, index, ds);
                }
                if (docstructs.containsKey("Farbkarte_Einband")) {
                    DocStruct ds = docstructs.get("Farbkarte_Einband");
                    index = setDocstructAndPagesToLogical(logical, physType, index, ds);
                }
                if (docstructs.containsKey("FR")) {
                    DocStruct ds = docstructs.get("FR");
                    index = setDocstructAndPagesToLogical(logical, physType, index, ds);
                }
                if (docstructs.containsKey("Fragm")) {
                    DocStruct ds = docstructs.get("Fragm");
                    index = setDocstructAndPagesToLogical(logical, physType, index, ds);
                }
            } catch (TypeNotAllowedAsChildException e) {
                log.error(e);
                return PluginReturnValue.ERROR;
            }
        }
        try {
            process.writeMetadataFile(ff);
        } catch (WriteException | PreferencesException | IOException | SwapException e) {
            log.error(e);
            return PluginReturnValue.ERROR;
        }

        return PluginReturnValue.FINISH;
    }

    private int setDocstructAndPagesToLogical(DocStruct logical, MetadataType physType, int index, DocStruct ds) {
        try {
            logical.addChild(ds);
            List<Reference> refs = ds.getAllToReferences("logical_physical");
            if (refs != null) {
                for (Reference ref : refs) {
                    DocStruct dsPage = ref.getTarget();
                    Metadata mdPhysPageNo = new Metadata(physType);
                    mdPhysPageNo.setValue(String.valueOf(index));
                    dsPage.addMetadata(mdPhysPageNo);
                    logical.addReferenceTo(dsPage, "logical_physical");
                    index = index + 1;
                }
            }
        } catch (TypeNotAllowedAsChildException | MetadataTypeNotAllowedException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }

        return index;
    }

    @Override
    public String cancel() {
        return null;
    }

    @Override
    public boolean execute() {
        PluginReturnValue val = run();

        return val == PluginReturnValue.FINISH;
    }

    @Override
    public String finish() {
        return null;
    }

    @Override
    public String getPagePath() {
        return null;
    }

    @Override
    public void initialize(Step step, String returnPath) {
        this.step = step;
    }

    @Override
    public HashMap<String, StepReturnValue> validate() {
        return null;
    }

    @Override
    public int getInterfaceVersion() {
        return 0;
    }
}
