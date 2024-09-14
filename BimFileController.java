package com.hotent.bimdocs.controller;


import com.autodesk.client.auth.Credentials;
import com.autodesk.client.model.Manifest;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.JsonNode;
import com.hotent.base.annotation.ApiGroup;
import com.hotent.base.attachment.AttachmentService;
import com.hotent.base.attachment.AttachmentServiceFactory;
import com.hotent.base.constants.ApiGroupConsts;
import com.hotent.base.exception.NotFoundException;
import com.hotent.base.handler.MultiTenantHandler;
import com.hotent.base.handler.MultiTenantIgnoreResult;
import com.hotent.base.model.CommonResult;
import com.hotent.base.util.*;
import com.hotent.bimdocs.common.constant.CommonConstant;
import com.hotent.bimdocs.config.FsServerPropertiesConfig;
import com.hotent.bimdocs.dao.BimFileMapper;
import com.hotent.bimdocs.model.*;
import com.hotent.bimdocs.model.request.*;
import com.hotent.bimdocs.model.response.BimFileDetailResponse;
import com.hotent.bimdocs.model.response.BimProjectIdResponse;
import com.hotent.bimdocs.model.response.FileHistoryResponse;
import com.hotent.bimdocs.model.response.FolderPermissionResponse;
import com.hotent.bimdocs.service.IBimFileService;
import com.hotent.bimdocs.service.IBimProjectService;
import com.hotent.bimdocs.utils.GenSourceDownloadPathUtil;
import com.hotent.bimdocs.utils.OpenAiClientUtil;
import com.hotent.file.attachmentService.FtpAttachmentServiceImpl;
import com.hotent.file.model.DefaultFile;
import com.hotent.file.persistence.manager.FileManager;
import com.hotent.file.service.FilePreview;
import com.hotent.file.service.FilePreviewFactory;
import com.hotent.file.util.AppFileUtil;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.util.ObjectUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import javax.activation.MimetypesFileTypeMap;
import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import javax.validation.Valid;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.URLEncoder;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * 文件管理
 *
 * @author dinghao
 * @date 2021/3/10
 */
@Api(tags = "文件及文件夹接口")
@ApiGroup(group = {ApiGroupConsts.GROUP_BIMDOCS})
@RestController
@RequestMapping("/bim_docs/system/file")
public class BimFileController {
    private static final Logger LOGGER = LoggerFactory.getLogger(BimFileController.class);

    @Resource
    private IBimFileService fileService;

    @Resource
    private BimFileMapper bimFileMapper;
    @Resource
    FilePreviewFactory previewFactory;

    @Resource
    private GenSourceDownloadPathUtil genSourceDownloadPathUtil;

    @Resource
    FileManager fileManager;

    @Value("${file.file.dir}")
    String fileDir;

    @Value("${files-server.local.uploadPath}")
    String bimPath;

    @Resource
    private RedissonClient redisson;

    @Resource
    private IBimProjectService bimProjectService;

    private static final String LOCK_KEY = "jiezi:upload:key";

    @Resource
    private FsServerPropertiesConfig fileProperties;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private OpenAiClientUtil openAiClientUtil;

    /**
     * 获取文件列表
     *
     * @param request
     * @return
     */
    @PostMapping("/list")
    @ApiOperation("获取文件列表")
    public Page<BimFilePojo> getList(@Valid @RequestBody BimFilePojoRequest request) throws Exception {
        Page<BimFilePojo> page = new Page<>(request.getPageBean().getPage(), request.getPageBean().getPageSize());
        return fileService.getList(page, request);
    }

    @PostMapping("/commentList")
    @ApiOperation("获取文件文件标注列表")
    public List<FilePojo> getCommentList(@RequestBody String... ids) {
        return fileService.getCommentList(Arrays.asList(ids));
    }

    @PostMapping("/searchList")
    @ApiOperation("获取文件列表")
    public List<BimFilePojo> getSearchList(@RequestBody BimFilePojo pojo) {
        return fileService.getSearchList(pojo);
    }

    /**
     * 获取文件列表
     *
     * @return
     */
    @PostMapping("/analysisList")
    @ApiOperation("获取待解析文件列表")
    public List<BimFilePojo> getAnalysisList() {
        return fileService.getAnalysisList();
    }

    /**
     * 获取树结构列表
     *
     * @param pojo
     * @return
     */
    @PostMapping("/getTree")
    @ApiOperation("获取树结构列表")
    public List<Dtree> getTree(@RequestBody BimFilePojo pojo) {
        return fileService.getTreeList(pojo);
    }

    /**
     * 获取树结构目录列表
     *
     * @param pojo
     * @return
     */
    @PostMapping("/getDirTree")
    @ApiOperation("获取树结构目录列表")
    public List<Dtree> getDirTree(@RequestBody @Valid BimFilePojo pojo) {
        return fileService.getDirTreeList(pojo);
    }

    /**
     * 获取目录列表
     *
     * @param pojo
     * @return
     */
    @PostMapping("/getDirs")
    @ApiOperation("获取目录列表")
    public List<Dtree> getDirs(@RequestBody BimFilePojo pojo) {
        return fileService.getDirList(pojo);
    }

    /**
     * 查询文件详情
     */
    @PostMapping("/getFileDetail")
    @ApiOperation("获取文件详情")
    public CommonResult getFileDetail(@RequestBody CommonIdRequest commonIdRequest) {
        CommonResult result;
        try {
            BimFileDetailResponse bimFilePojo = fileService.getFileDetail(commonIdRequest.getId());
            if (StringUtil.isNotNull(bimFilePojo)) {
                result = new CommonResult<>(true, "文件详情查询成功!", bimFilePojo);
            } else {
                result = new CommonResult<>(false, "文件详情查询成功!");
            }
        } catch (Exception e) {
            result = new CommonResult<>(false, e.getMessage());
        }
        return result;
    }

    @PostMapping("/buildFolderStruct")
    @ApiOperation("文件夹上传构建文件夹数据结构")
    public CommonResult buildFolderStruct(@RequestBody FolderStruct folder) {
        CommonResult result;
        try {
            fileService.buildFolderStruct(folder);
            result = new CommonResult<>(true, "文件夹上传构建文件夹数据结构成功");
        } catch (Exception e) {
            LOGGER.error(e.getMessage());
            result = new CommonResult<>(false, e.getMessage());
        }
        return result;
    }

    /**
     * 文件上传
     *
     * @param files
     * @param dirIds
     * @return
     */
    @PostMapping({"", "/upload"})
    @ApiOperation("文件上传")
    public CommonResult upload(@RequestParam(value = "file") MultipartFile[] files, @RequestParam(value = "dirIds") String dirIds,
                               @RequestParam(value = "projectId") String projectId, @ApiParam(value = "上传类型，0-单个上传，1-文件夹上传") @RequestParam(value = "uploadType") Integer uploadType) {
        CommonResult result;
        try {
            result = new CommonResult<>(true, "文件上传成功!", fileService.uploadFilePojo(files, dirIds, projectId, uploadType));
        } catch (Exception e) {
            LOGGER.error(e.getMessage());
            result = new CommonResult<>(false, e.getMessage());
        }
        return result;
    }

    /**
     * 大文件断点续传
     *
     * @return
     */
    @PostMapping("/breakPointUpload")
    @ApiOperation("大文件断点续传")
    public CommonResult breakPointUpload(UploadFileParam param) {
        CommonResult result = null;
        BimFilePojo pojo = null;
        try {
            RLock lock = redisson.getLock(LOCK_KEY);
            boolean islock = lock.tryLock(200, TimeUnit.SECONDS);
            if (islock) {
                try {
                    pojo = fileService.breakPointUpload(param);
                    result = new CommonResult<>(true, "请求成功", pojo);
                } finally {
                    lock.unlock();
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            pojo = new BimFilePojo();
            pojo.setPutBy(AuthenticationUtil.getCurrentUserId());
            result = new CommonResult<>(false, e.getMessage(), pojo);
            LOGGER.error("BimFileController.breakPointUpload:::::::::" + e.getMessage());
        }
        return result;
    }


    @PostMapping(value = "/getUuidfileName")
    @ApiOperation("获取uuid生成的文件名")
    public CommonResult getUuidfileName(@RequestBody UploadFileParam param) throws Exception {
        try {
            String uuidfileName = fileService.queryUuidfileName(param);
            return new CommonResult<>(true, uuidfileName);
        } catch (Exception e) {
            return new CommonResult<>(false, e.getMessage());
        }
    }

    @GetMapping(value = "/chunkSize")
    @ApiOperation("获取大文件分片上传的分片大小")
    public CommonResult<Map<String, Long>> chunkSize() {
        return new CommonResult<Map<String, Long>>(true, null, fileService.chunkSize());
    }


    @PostMapping(value = "/packageFolder")
    @ApiOperation("打包文件夹")
    public CommonResult<String> packageFolder(String[] dirIds) {
        if (dirIds == null) {
            return new CommonResult<String>(false, "文件夹数量为空");
        }
        try {
            return new CommonResult<String>(true, "文件夹打包中", fileService.packageFolder(dirIds));
        } catch (Exception e) {
            LOGGER.error("BimFileController.packageFolder:::::::::" + e.getMessage());
            return new CommonResult<String>(false, e.getMessage());
        }

    }


    @PostMapping(value = "/queryPackageFolders")
    @ApiOperation("查询打包列表")
    public Page<BimFilePackage> queryPackageFolders(@Valid @RequestBody BimFileRequest request) {
        try {
            return fileService.queryPackageFolders(request);
        } catch (Exception e) {
            LOGGER.error("BimFileController.queryPackageFolders:::::::::" + e.getMessage());
            return null;
        }
    }

    @GetMapping(value = "/getUploadDiskInfo")
    @ApiOperation("获取大文件分片上传的分片大小")
    public CommonResult<Map<String, Long>> getUploadDiskInfo() {
        return new CommonResult<Map<String, Long>>(true, null, fileService.getUploadDiskInfo());
    }

    @PostMapping(value = "/downLoadPackage")
    @ApiOperation("下载打包好的文件")
    public void downLoadPackage(@Valid @RequestBody BimFileRequest request, HttpServletResponse response) {
        fileService.downLoadPackage(request, response);
    }

    @PostMapping(value = "/queryUploadRecords")
    @ApiOperation("查询上传记录列表")
    public Page<BimFileRecord> queryUploadRecord(@Valid @RequestBody BimFileRequest request) {
        try {
            return fileService.queryUploadRecord(request);
        } catch (Exception e) {
            LOGGER.error("BimFileController.queryUploadRecord:::::::::" + e.getMessage());
            return null;
        }
    }


    /**
     * 获取进度数据
     *
     * @param request
     * @return
     */
    @GetMapping("/percent")
    @ApiOperation("获取进度数据")
    public Integer percent(HttpServletRequest request) {
        HttpSession session = request.getSession();
        return (session.getAttribute("uploadPercent") == null ? 0 : (Integer) session.getAttribute("uploadPercent"));
    }

    /**
     * 重置上传进度
     *
     * @param request
     */
    @GetMapping("/percent/reset")
    @ApiOperation("重置上传进度")
    public void resetPercent(HttpServletRequest request) {
        HttpSession session = request.getSession();
        session.setAttribute("uploadPercent", 0);
        // ossUploadUtils.initPart();
    }

    @GetMapping("/file_download/{fileId}")
    public void fileDownload(@PathVariable("fileId") String fileId, HttpServletResponse response) {
        fileService.fileDownload(fileId, response);
    }

    @GetMapping("/fileZip_download/{fileId}")
    @ApiOperation("文件夹压缩下载")
    public void fileZipDownload(@PathVariable("fileId") String fileId, HttpServletResponse response) {
        fileService.fileZipDownload(fileId, response);
    }

    @PostMapping("/fileUrl_download")
    @ApiOperation("返回文件压缩url")
    public CommonResult fileUrlDownload(@RequestBody BimFilePojo pojo) throws Exception {
        CommonResult result = new CommonResult();
        if (pojo.getDownLoadType() == 1) {
            result.setValue(fileService.fileUrlDownLoadPackage(pojo));
        } else {
            result.setValue(fileService.fileUrlDownload(pojo));
        }
        return result;
    }


    /**
     * 修改名称
     *
     * @param pojo
     */
    @PostMapping("/updateByName")
    @ApiOperation("修改名称")
    public CommonResult<String> updateByName(@RequestBody BimFileUpdateNamePojo pojo) {
        CommonResult<String> result;
        try {
            LOGGER.info("===================docs用户：" + AuthenticationUtil.getCurrentUserFullname() + "==文件名称修改：" + pojo.getName() + " 修改为==>" + pojo.getRename() + "=========================");
            boolean uploadFile = fileService.updateByName(pojo);
            if (uploadFile) {
                result = new CommonResult<>(true, "文件名称修改成功!");
            } else {
                result = new CommonResult<>(false, "文件名称修改失败!");
            }
        } catch (Exception e) {
            result = new CommonResult<>(false, e.getMessage());
        }
        return result;

    }

    /**
     * 移动文件
     */
    @PostMapping("/move")
    @ApiOperation("移动文件")
    public CommonResult<String> move(@RequestBody BimFileMovePojo bimFileMovePojo) {
        CommonResult<String> result;
        try {
            LOGGER.info("===================docs用户：" + AuthenticationUtil.getCurrentUserFullname() + "==文件移动：" + bimFileMovePojo.getIds() + " 移动到==>" + bimFileMovePojo.getParentId() + "=========================");
            boolean move = fileService.move(bimFileMovePojo);
            if (move) {
                result = new CommonResult<>(true, "移动成功!");
            } else {
                LOGGER.error("===================docs用户：" + AuthenticationUtil.getCurrentUserFullname() + "==文件移动失败：" + bimFileMovePojo.getIds() + " 移动到==>" + bimFileMovePojo.getParentId() + "=========================");
                result = new CommonResult<>(false, "移动失败!");
            }
        } catch (Exception e) {
            result = new CommonResult<>(false, e.getMessage());
        }
        return result;
    }

    @PostMapping("/copy")
    @ApiOperation("文件复制")
    public CommonResult<String> copy(@RequestBody BimFileMovePojo bimFileMovePojo) {
        CommonResult<String> result;
        try {
            LOGGER.info("===================" + AuthenticationUtil.getCurrentUserFullname() + "==docs文件复制：" + bimFileMovePojo.getIds() + "=========================");
            boolean copy = fileService.copy(bimFileMovePojo);
            if (copy) {
                result = new CommonResult<>(true, "文件复制成功!");
            } else {
                LOGGER.error("====================docs用户：" + AuthenticationUtil.getCurrentUserFullname() + "==文件复制失败：" + bimFileMovePojo.getIds() + "=========================");
                result = new CommonResult<>(false, "文件复制失败!");
            }
        } catch (Exception e) {
            result = new CommonResult<>(false, e.getMessage());
        }
        return result;
    }


    /**
     * 根据id删除文件
     *
     * @param pojo
     */
    @PostMapping("/deleteByIds")
    @ApiOperation("根据id删除文件")
    public CommonResult<String> deleteByIds(@RequestBody BimFilePojo pojo) {
        CommonResult<String> result;
        try {
            LOGGER.info("===================docs用户：" + AuthenticationUtil.getCurrentUserFullname() + "文件删除：" + pojo.getId() + "=========================");
            boolean deleteFile = fileService.deleteByIds(pojo.getId());
            if (deleteFile) {
                result = new CommonResult<>(true, "删除成功!");
            } else {
                LOGGER.error("===================docs用户：" + AuthenticationUtil.getCurrentUserFullname() + "文件删除失败：" + pojo.getId() + "=========================");
                result = new CommonResult<>(false, "删除失败!");
            }
        } catch (Exception e) {
            LOGGER.error("文件删除失败!Param:" + pojo, e);
            result = new CommonResult<>(false, "文件删除失败!");
        }
        return result;

    }

    /**
     * 新增文件夹
     *
     * @param pojo
     */
    @PostMapping("/addFolder")
    @ApiOperation("新增文件夹")
    public CommonResult<String> addFolder(@RequestBody @Valid BimFilePojo pojo) {
        CommonResult<String> result;
        try {
            String id = fileService.addFolder(pojo);
            if (StringUtil.isNotEmpty(id) && !id.equals("0")) {
                result = new CommonResult<>(true, "新增文件夹成功!", id);
            } else {
                result = new CommonResult<>(false, "新增文件夹失败!");
            }
        } catch (Exception e) {
            result = new CommonResult<>(false, e.getMessage());
        }
        return result;
    }

    /**
     * 新增多级文件夹
     *
     * @param pojo
     */
    @PostMapping("/addFolderList")
    @ApiOperation("新增多级文件夹")
    public CommonResult<List<Dtree>> addFolderList(@RequestBody @Valid BimFilePojo pojo) {
        CommonResult<List<Dtree>> result = new CommonResult<>();
        try {
            List<Dtree> bimFilePojo = fileService.addFolderList(pojo);
            for (Dtree dtree : bimFilePojo) {
                if (!ObjectUtils.isEmpty(dtree.getChildrenList())) {
                    dtree.setFilePath(dtree.getName());
                    filePath(dtree.getChildrenList(), dtree.getName());
                }
            }
            result.setValue(bimFilePojo);
        } catch (Exception e) {
            result = new CommonResult<>(false, e.getMessage());
        }
        return result;
    }

    public void filePath(List<BimFilePojo> pojoList, String path) {
        for (BimFilePojo pojo : pojoList) {
            pojo.setFilePath(path + "/" + pojo.getName());
            if (!ObjectUtils.isEmpty(pojo.getChildrenList())) {
                filePath(pojo.getChildrenList(), pojo.getFilePath());
            }
        }
    }

    /**
     * 文件资源同步
     *
     * @param pojo
     */
    @PostMapping("/syncFolder")
    @ApiOperation("文件同步")
    public CommonResult<Boolean> syncFolder(@RequestBody @Valid BimFilePojo pojo) {
        CommonResult<Boolean> result = new CommonResult<>();
        try {
            Boolean folder = fileService.syncFolder(pojo);
            if (folder) {
                result = new CommonResult<>(true, "创建成功!");
            } else {
                result = new CommonResult<>(false, "创建失败!");
            }
        } catch (Exception e) {
            result = new CommonResult<>(false, e.getMessage());
        }
        return result;
    }

    @PostMapping("/forge/get_access_token")
    @ApiOperation("获取forge的accessToken")
    public CommonResult getAccessToken() {
        CommonResult result;
        try {
            Credentials accessToken = fileService.getAccessToken();
            if (StringUtil.isNotNull(accessToken)) {
                result = new CommonResult<>(true, "获取AccessToken成功!", accessToken);
            } else {
                result = new CommonResult<>(false, "获取AccessToken失败!");
            }
        } catch (Exception e) {
            result = new CommonResult<>(false, e.getMessage());
        }
        return result;
    }

    @PostMapping("/forge/get_flash_access_token")
    @ApiOperation("获取forge的accessToken")
    public CommonResult getFlashAccessToken() {
        CommonResult result;
        try {
            Credentials accessToken = fileService.getFlashAccessToken();
            if (StringUtil.isNotNull(accessToken)) {
                result = new CommonResult<>(true, "获取AccessToken成功!", accessToken);
            } else {
                result = new CommonResult<>(false, "获取AccessToken失败!");
            }
        } catch (Exception e) {
            result = new CommonResult<>(false, e.getMessage());
        }
        return result;
    }

    @PostMapping("/forge/get_progress")
    @ApiOperation("获取forge的解析进度")
    public CommonResult getProgress(@RequestBody CommonProjectIdRequest commonProjectIdRequest) {
        CommonResult result;
        try {
            List<FileUploadCheck> progress = fileService.getProgress(commonProjectIdRequest);
            result = new CommonResult<>(true, "获取Progress成功!", progress);
        } catch (Exception e) {
            result = new CommonResult<>(false, e.getMessage());
        }
        return result;
    }

    @PostMapping("/forge/re_building")
    @ApiOperation("重新解析")
    public CommonResult reBuilding(@RequestBody CommonIdRequest commonIdRequest) {
        CommonResult result;
        try {
            Boolean getReBuilding = fileService.reBuilding(commonIdRequest);
            if (StringUtil.isNotNull(getReBuilding)) {
                result = new CommonResult<>(true, "重新解析成功!");
            } else {
                result = new CommonResult<>(false, "重新解析失败!");
            }
        } catch (Exception e) {
            result = new CommonResult<>(false, e.getMessage());
        }
        return result;
    }

    @PostMapping("/history_list")
    @ApiOperation("获取版本数据")
    public CommonResult historyList(@RequestBody CommonIdRequest commonIdRequest) {
        CommonResult result;
        try {
            List<FileHistoryResponse> filePojoList = fileService.getHistoryList(commonIdRequest.getId());
            if (filePojoList.size() > 0) {
                result = new CommonResult<>(true, "获取版本数据成功!", filePojoList);
            } else {
                result = new CommonResult<>(false, "版本数据不存在!");
            }
        } catch (Exception e) {
            result = new CommonResult<>(false, e.getMessage());
        }
        return result;
    }

    @PostMapping("/change_version")
    @ApiOperation("切换版本")
    public CommonResult changeVersion(@RequestBody CommonIdVersionRequest commonIdVersionRequest) {
        CommonResult result;
        try {
            Boolean b = fileService.changeVersion(commonIdVersionRequest);
            if (b) {
                result = new CommonResult<>(true, "切换版本成功!");
            } else {
                result = new CommonResult<>(false, "切换版本失败!");
            }
        } catch (Exception e) {
            result = new CommonResult<>(false, e.getMessage());
        }
        return result;
    }

    @PostMapping("/add_instruction")
    @ApiOperation("增加说明")
    public CommonResult addInstruction(@RequestBody BimFileUpdateInstructionPojo bimFileUpdateInstructionPojo) {
        CommonResult result;
        try {
            int i = fileService.addInstruction(bimFileUpdateInstructionPojo);
            if (i > 0) {
                result = new CommonResult<>(true, "增加说明成功!");
            } else {
                result = new CommonResult<>(false, "增加说明失败!");
            }
        } catch (Exception e) {
            result = new CommonResult<>(false, e.getMessage());
        }
        return result;
    }

    @PostMapping("/check_permission")
    @ApiOperation("校验当前文件夹权限")
    public CommonResult checkPermission(@RequestBody CommonIdRequest commonIdRequest) {
        CommonResult result;
        try {
            FolderPermissionResponse folderPermissionResponse = fileService.checkPermission(commonIdRequest);
            if (StringUtil.isNotNull(folderPermissionResponse)) {
                result = new CommonResult<>(true, "校验当前文件夹权限成功!", folderPermissionResponse);
            } else {
                result = new CommonResult<>(false, "校验当前文件夹权限失败!");
            }
        } catch (Exception e) {
            result = new CommonResult<>(false, e.getMessage());
        }
        return result;
    }


    @RequestMapping(value = "/onlinePreview", method = RequestMethod.GET, produces = {"application/json; charset=utf-8"})
    @ApiOperation(value = "附件在线预览", httpMethod = "GET", notes = "附件在线预览")
    public JsonNode onlinePreview(HttpServletRequest request, HttpServletResponse response, @ApiParam(name = "fileId", value = "附件ID") @RequestParam String fileId) throws Exception {
        Map<String, String> map = new HashMap<String, String>();
        DefaultFile fileMode = fileService.getFile(fileId);
        fileMode.setBimType(bimPath);
        if (fileMode != null) {
            AttachmentServiceFactory attachmentHandlerFactory = AppUtil.getBean(AttachmentServiceFactory.class);
            AttachmentService attachmentService = attachmentHandlerFactory.getCurrentServices(AppFileUtil.getSaveType(BeanUtils.isNotEmpty(fileMode) ? fileMode.getProp6() : ""));
            //特殊场景，附件上传方式是ftp，word套打生成的文件是磁盘存储，下载套打文件时特殊处理
            if (attachmentService instanceof FtpAttachmentServiceImpl && DefaultFile.SAVE_TYPE_FOLDER.equals(fileMode.getStoreType())) {
                attachmentService = attachmentHandlerFactory.getCurrentServices(DefaultFile.SAVE_TYPE_FOLDER);
            }
//            boolean ref = fileService.checkBimFile(fileMode,BeanUtils.isNotEmpty(fileMode)?fileMode.getProp6():"");
            String filePath = bimPath + File.separator + fileMode.getBimType();
            if (StringUtil.isNotEmpty(filePath)) {
                FilePreview filePreview = previewFactory.get(fileMode);
                String Result = filePreview.filePreviewHandle(fileMode, map);
                map.put("result", Result);
                map.remove("project");
            } else {
                map.put("result", "error");
            }
        }
        JsonNode object = JsonUtil.toJsonNode(map);
        return object;
    }

    @RequestMapping(value = "getFileByPathAndId_{fileId}_{ext}", method = RequestMethod.GET, produces = {"application/json; charset=utf-8"})
    @ApiOperation(value = "根据ID和类型找到处理后的附件", httpMethod = "GET", notes = "根据ID和类型找到附件")
    public void getFileByPathAndId(HttpServletResponse response, @PathVariable(name = "fileId") String fileId, @PathVariable(name = "ext") String ext) throws IOException {
        String fullPath = fileDir + fileId + "." + ext;
        String type = "text/html;charset=" + getCharset(fullPath);
        if ("pdf".equals(ext)) {
            type = "application/pdf";
        }
        response.setContentType(type);
        byte[] bytes = FileUtil.readByte(fullPath);
        if (bytes != null && bytes.length > 0) {
            response.getOutputStream().write(bytes);
        }

    }

    @RequestMapping(value = "getFileById_{fileId}", method = RequestMethod.GET, produces = {"application/json; charset=utf-8"})
    @ApiOperation(value = "根据文件ID找到上传过的文件", httpMethod = "GET", notes = "根据文件ID找到上传过的文件")
    public void getFileById(HttpServletRequest request, HttpServletResponse response, @PathVariable(name = "fileId") String fileId) throws Exception {
        DefaultFile file = null;
        try (MultiTenantIgnoreResult setThreadLocalIgnore = MultiTenantHandler.setThreadLocalIgnore()) {
            file = fileService.getFile(fileId);
        }
        if (BeanUtils.isEmpty(file)) {
            throw new NotFoundException(String.format("未找到fileId为: %s 的文件", fileId));
        }
        String fileName = file.getFileName() + "." + file.getExtensionName();
        String filedisplay = URLEncoder.encode(fileName, "utf-8");
        response.setHeader("Access-Control-Expose-Headers", "Content-Disposition");
        response.addHeader("filename", filedisplay);
        response.setHeader("Access-Control-Allow-Origin", "*");
        String type = new MimetypesFileTypeMap().getContentType(new File(file.getFilePath()));
        response.setContentType(type);
        fileService.fileOnline(fileId, response);
    }

    /**
     * 关联流程引擎
     * 切換項目
     * 保存人员
     * 更新人员
     * 查询人员
     */
    @PostMapping("/check_bim_project_id")
    @ApiOperation(value = "根据流程引擎项目查360项目id", httpMethod = "POST")
    public CommonResult checkBimProjectId(@RequestBody CommonIdRequest commonIdRequest) {
        CommonResult result;
        try {
            BimProjectIdResponse bimProjectIdResponse = fileService.checkBimProjectId(commonIdRequest);
            if (StringUtil.isNotNull(bimProjectIdResponse)) {
                result = new CommonResult<>(true, "查询BIM360项目id成功!", bimProjectIdResponse);
            } else {
                result = new CommonResult<>(false, "查询BIM360项目id失败!");
            }
        } catch (Exception e) {
            result = new CommonResult<>(false, e.getMessage());
        }
        return result;
    }

    @PostMapping("/get_bim_thumbnail")
    @ApiOperation(value = "查询文件缩略图", httpMethod = "POST")
    public CommonResult getBimThumbnail(@RequestBody ForgeUrn forgeUrn) {
        CommonResult result;
        try {
            Manifest manifest = fileService.getBimThumbnail(forgeUrn);
            if (StringUtil.isNotNull(manifest)) {
                result = new CommonResult<>(true, "查询文件缩略图成功!", manifest);
            } else {
                result = new CommonResult<>(false, "查询文件缩略图失败!");
            }
        } catch (Exception e) {
            result = new CommonResult<>(false, e.getMessage());
        }
        return result;
    }

    @GetMapping("/getTempCheckFileList")
    @ApiOperation(value = "查询文件缩略图", httpMethod = "GET")
    public CommonResult getTempCheckFileList() {
        CommonResult result;
        try {
            List<BimFilePojo> bimFilePojoList = fileService.getTempCheckFileList();
            if (ListsUtils.isArrayListNotEmpty(bimFilePojoList)) {
                result = new CommonResult<>(true, "文件处理成功!", bimFilePojoList);
            } else {
                result = new CommonResult<>(false, "文件处理失败!");
            }
        } catch (Exception e) {
            result = new CommonResult<>(false, e.getMessage());
        }
        return result;
    }


    //根据文件路径获取文件编码格式
    public static String getCharset(String pathName) {
        File file = new File(pathName);
        if (!file.exists()) {
            return "";
        }
        String charset = "GBK";
        byte[] first3Bytes = new byte[3];
        BufferedInputStream bis = null;
        try {
            boolean checked = false;
            bis = new BufferedInputStream(new FileInputStream(file));
            bis.mark(0);
            int read = bis.read(first3Bytes, 0, 3);
            if (read == -1)
                return charset;
            if (first3Bytes[0] == (byte) 0xFF
                    && first3Bytes[1] == (byte) 0xFE) {
                charset = "UTF-16LE";
                checked = true;
            } else if (first3Bytes[0] == (byte) 0xFE
                    && first3Bytes[1] == (byte) 0xFF) {
                charset = "UTF-16BE";
                checked = true;
            } else if (first3Bytes[0] == (byte) 0xEF
                    && first3Bytes[1] == (byte) 0xBB
                    && first3Bytes[2] == (byte) 0xBF) {
                charset = "UTF-8";
                checked = true;
            }
            bis.reset();
            if (!checked) {
                while ((read = bis.read()) != -1) {
                    if (read >= 0xF0)
                        break;
                    if (0x80 <= read && read <= 0xBF)
                        break;
                    if (0xC0 <= read && read <= 0xDF) {
                        read = bis.read();
                        if (0x80 <= read && read <= 0xBF)
                            continue;
                        else
                            break;
                    } else if (0xE0 <= read && read <= 0xEF) {
                        read = bis.read();
                        if (0x80 <= read && read <= 0xBF) {
                            read = bis.read();
                            if (0x80 <= read && read <= 0xBF) {
                                charset = "UTF-8";
                                break;
                            } else
                                break;
                        } else
                            break;
                    }
                }
            }

            bis.close();
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            try {
                bis.close();
            } catch (IOException e) {
            }
        }

        return charset;
    }


    @ApiOperation("同步文件")
    @PostMapping("/syncFile")
    public CommonResult syncFile() {
        CommonResult result;
        try {
            boolean b = fileService.syncFile();
            if (b) {
                result = new CommonResult<>(true, "同步文件成功!");
            } else {
                result = new CommonResult<>(false, "同步文件失败!");
            }
        } catch (Exception e) {
            result = new CommonResult<>(false, e.getMessage());
        }
        return result;
    }

    @ApiOperation("检查文件")
    @PostMapping("/filterFile")
    public Page<FileHistoryResponse> filterFile(Page page) {
        Page<FileHistoryResponse> fileHistoryResponsePage = fileService.filterFile(page);
        return fileHistoryResponsePage;
    }

    @ApiOperation("删除不存在文件")
    @PostMapping("/deleteFile")
    public CommonResult deleteFile() {
        CommonResult result;
        try {
            boolean b = fileService.deleteFile();
            if (b) {
                result = new CommonResult<>(true, "删除文件成功!");
            } else {
                result = new CommonResult<>(false, "删除文件失败!");
            }
        } catch (Exception e) {
            result = new CommonResult<>(false, e.getMessage());
        }
        return result;
    }

    @ApiOperation("恢复文件存在数据")
    @PostMapping("/restoreFiles")
    public CommonResult restoreFiles() {
        CommonResult result;
        try {
            boolean b = fileService.restoreFiles();
            if (b) {
                result = new CommonResult<>(true, "修复文件成功!");
            } else {
                result = new CommonResult<>(false, "修复文件失败!");
            }
        } catch (Exception e) {
            result = new CommonResult<>(false, e.getMessage());
        }
        return result;
    }

    @ApiOperation("获取文件信息")
    @GetMapping("/getFileById/{id}")
    public BimFilePojo getFileById(@PathVariable String id) {
        BimFilePojo filePojo = bimFileMapper.selectBimFileById(id);
        if (!ObjectUtils.isEmpty(filePojo)) {
            BimProject project = bimProjectService.getById(filePojo.getProjectId());
            filePojo.setProjectName(!ObjectUtils.isEmpty(project.getName()) ? project.getName() : null);
        }
        return filePojo;
    }

    @ApiOperation("获取文件下载信息")
    @GetMapping("/getFileDownloadById/{id}")
    public CommonResult<String> getFileDownloadById(@PathVariable String id) {
        BimFilePojo filePojo = fileService.getFullNameById(id);
        if (StringUtil.isNull(filePojo)) {
            return new CommonResult<>(false, "该文件不存在！");
        }
        String execute = genSourceDownloadPathUtil.execute(filePojo.getFullName());
        return new CommonResult<>(true, execute);
    }

    /**
     * 根据传入参数开始时间、结束时间区间查询文件是否存在
     *
     * @param dataStartToEnd 参数类
     */
    @ApiOperation("(临时)校验文件是否存在")
    @PostMapping("/checkFileExist")
    public void checkFileExist(@RequestBody DataStartToEnd dataStartToEnd) {
        //1.【查系统文件列表】查询数据库存储的文件列表
        List<DataStartToEnd> fileList = fileService.getDataStartToEndList(dataStartToEnd);
        //如果列表不为空
        if (ListsUtils.isArrayListNotEmpty(fileList)) {
            //2.【查服务器存放地址】读取服务器存放文件夹路径
            File path = new File(fileProperties.getLocal().getUploadPath());
            if (!path.exists()) {
                LOGGER.info("===========================文件目录不存在！======================");
                throw new RuntimeException("文件目录不存在");
            }
            //3.【系统文件列表和服务器文件对比】迭代数据库文件列表
            Iterator<DataStartToEnd> iterator = fileList.iterator();
            while (iterator.hasNext()) {
                DataStartToEnd next = iterator.next();
                next.getFileName();
                //4.拼接服务器文件夹+文件名 查找文件
                File file = new File(path + CommonConstant.DIR_SPLIT + next.getFileName());
                if (!file.exists()) {
                    //5.不存在生成记录并存储日志
                    fileService.updateFileExist(next.getId());
                    LOGGER.info("===========================id是{" + next.getId() + "}文件不存在======================");
                }
            }
        } else {
            throw new RuntimeException("查询文件列表为空");
        }

    }

    @ApiOperation("(临时)数据还原")
    @PostMapping("/rollBack")
    public CommonResult<String> rollBack(@RequestBody DataStartToEnd dataStartToEnd) {

        Boolean b = fileService.checkFileBack(dataStartToEnd);
        if (b) {
            return new CommonResult<>(true, "执行成功");
        } else {
            return new CommonResult<>(false, "执行失败");
        }
    }

    @ApiOperation("(临时)导出数据")
    @PostMapping("/exportAll")
    public CommonResult<List<BimFilePojo>> exportAll(@RequestBody DataStartToEnd dataStartToEnd) {

        List<BimFilePojo> bimFilePojoList = fileService.exportAll(dataStartToEnd);
        if (ListsUtils.isArrayListNotEmpty(bimFilePojoList)) {
            return new CommonResult<>(true, "执行成功", bimFilePojoList);
        } else {
            return new CommonResult<>(false, "执行失败");
        }
    }

    @ApiOperation("(临时)校验实际文件大小")
    @PostMapping("/checkTureSize")
    public CommonResult<String> checkTureSize(@RequestBody DataStartToEnd dataStartToEnd) {

        Boolean b = fileService.checkTureSize(dataStartToEnd);
        if (b) {
            return new CommonResult<>(true, "校验实际文件大小成功");
        } else {
            return new CommonResult<>(false, "校验实际文件大小失败");
        }
    }


    @PostMapping(value = "/rollbackUrl")
    @ApiOperation("模型回调地址处理")
    public CommonResult rollbackUrl(@RequestParam(value = "status", required = false) Integer status, String errorDescription, String errorDetail, String outputFiles, int taskID) {
        try {
            Boolean b = fileService.rollbackUrl(status, errorDescription, errorDetail, outputFiles, taskID);
            if (b) {
                return new CommonResult<>(true, "模型回调地址处理成功");
            } else {
                return new CommonResult<>(false, "模型回调地址处理失败");
            }
        } catch (Exception e) {
            LOGGER.error("===========================模型回调地址处理异常======================" + "状态：{}" + "errorDescription：{}" + "errorDetail:{}" + "outputFiles:{}" + "taskID:{}", status, errorDescription, errorDetail, outputFiles, taskID, e);
        }

        return new CommonResult<>(false, "模型回调地址处理失败");
    }

    @ApiOperation("使用新迪引擎重新解析文件")
    @GetMapping("/reparseModel/{id}")
    public CommonResult<String> reparseModel(@PathVariable String id) {
        Boolean b = fileService.reparseModel(id);
        if (b) {
            return new CommonResult<>(true, "文件开始解析");
        } else {
            return new CommonResult<>(false, "文件解析异常");
        }
    }

    @ApiOperation("查询解析文件是否成功")
    @GetMapping("/checkModel/{id}")
    public CommonResult<BimFilePojo> checkModel(@PathVariable String id) {
        BimFilePojo bimFilePojo = fileService.checkModel(id);
        if (StringUtil.isNotNull(bimFilePojo)) {
            return new CommonResult<>(true, "查询成功", bimFilePojo);
        } else {
            return new CommonResult<>(false, "查询失败");
        }
    }

    @ApiOperation("重新生成索引")
    @PostMapping("/rebuildIndex")
    public CommonResult<String> rebuildIndex(@RequestBody ReBuildIndexRequest buildIndexRequest) {
        Boolean b = fileService.rebuildIndex(buildIndexRequest);
        if (b) {
            return new CommonResult<>(true, "重新生成索引成功");
        } else {
            return new CommonResult<>(false, "重新生成索引失败");
        }
    }

    @ApiOperation("手动执行定时任务解析")
    @PostMapping("/execScheduleParse")
    public void execScheduleParse() {
        fileService.refreshExecFileParse();
    }

    @ApiOperation("重新执行模型解析")
    @PostMapping("/rebuildModelParse")
    public CommonResult rebuildModelParse(@RequestBody List<String> fileIds) {
        LOGGER.info("===========================重新执行模型解析开始======================【{}】", fileIds);
        CommonResult<String> result;
        try {
            fileService.rebuildModelParse(fileIds);
            result = new CommonResult<>(true, "解析成功!");
        } catch (Exception e) {
            LOGGER.error("解析失败!Param:{}", fileIds, e);
            result = new CommonResult<>(false, "解析失败!");
        }
        LOGGER.info("===========================重新执行模型解析结束======================【{}】", fileIds);
        return result;
    }

    @ApiOperation("重新执行向量解析")
    @PostMapping("/rebuildVectorParse")
    public CommonResult rebuildVectorParse(@RequestBody List<String> fileIds) {
        LOGGER.info("===========================重新执行向量解析开始======================【{}】", fileIds);
        CommonResult<String> result;
        try {
            fileService.rebuildVectorParse(fileIds);
            result = new CommonResult<>(true, "解析成功!");
        } catch (Exception e) {
            LOGGER.error("解析失败!Param:{}", fileIds, e);
            result = new CommonResult<>(false, "解析失败!");
        }
        LOGGER.info("===========================重新执行向量解析结束======================【{}】", fileIds);
        return result;
    }
}
