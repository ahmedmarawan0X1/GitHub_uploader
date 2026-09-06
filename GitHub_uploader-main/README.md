# GitHub uploader

## 🇪🇬 شرح التطبيق بالعربية

**GitHub uploader** هو تطبيق أندرويد مخصص لرفع مشاريع **GitHub** من الهاتف مباشرةً باستخدام ملف **ZIP**.

فكرة التطبيق بسيطة: بدل ما تفك ضغط المشروع وتنقل ملفاته إلى GitHub يدويًا، تختار ملف المشروع المضغوط من الهاتف، تدخل بيانات الوصول إلى حساب GitHub، ثم تختار إذا كنت تريد إنشاء مستودع جديد أو تحديث مستودع موجود.

### طريقة استخدام التطبيق

1. افتح التطبيق وأدخل **GitHub Personal Access Token** الخاص بك.
2. اختر ملف المشروع بصيغة **ZIP** من ذاكرة الهاتف.
3. حدد طريقة التعامل مع المشروع:
   - **إنشاء Repository جديد:** اكتب اسم المستودع والوصف وحدد إذا كان المستودع خاصًا أو عامًا.
   - **تحديث Repository:** اختر المستودع الذي تريد تحديثه من المستودعات المرتبطة بحسابك.
4. اضغط على زر تنفيذ عملية الرفع.
5. أثناء العملية يعرض التطبيق حالة الرفع والتقدم.
6. بعد انتهاء العملية، يظهر رابط المستودع الذي تم التعامل معه، ويمكن الضغط عليه لفتحه مباشرةً في متصفح الهاتف.

### الوصول إلى GitHub

التطبيق يتعامل مع GitHub من خلال **GitHub API**، لذلك يحتاج إلى **Personal Access Token** يسمح له بالوصول إلى المستودعات المطلوبة.

يمكن إنشاء الـ Token من GitHub، ثم وضعه في خانة الـ Token داخل التطبيق. وفي حالة استخدام **Fine-grained Token** يجب إعطاؤه الصلاحيات المناسبة للمستودعات التي تريد التعامل معها، خصوصًا صلاحية قراءة وكتابة محتوى المستودعات.

### إنشاء مشروع جديد أو تحديث مشروع موجود

عند إنشاء Repository جديد، يقوم التطبيق بإنشاء المستودع ثم يرفع محتويات ملف ZIP إليه.

وعند تحديث Repository، يختار التطبيق المستودع المطلوب ثم يرفع محتويات ملف ZIP إليه لتحديث ملفات المشروع الموجودة على GitHub.

### رابط المشروع

الرابط الافتراضي للمشروع هو:

`https://github.com/jhftyyyty/GitHub_uploader`

رابط المشروع ثابت داخل التطبيق ويظهر في الإعدادات للرجوع إليه.

---

## 🇬🇧 App Description in English

**GitHub uploader** is an Android application designed to upload **GitHub projects** directly from a phone using a **ZIP** project file.

The idea is simple: instead of extracting a project and manually moving its files to GitHub, you select the ZIP file from your device, provide your GitHub access credentials, and then choose whether you want to create a new repository or update an existing one.

### How to Use the App

1. Open the app and enter your **GitHub Personal Access Token**.
2. Select the project **ZIP** file from your phone.
3. Choose how you want to handle the project:
   - **Create a new Repository:** enter the repository name and description, then choose whether it should be private or public.
   - **Update an existing Repository:** select the repository you want to update from the repositories available in your GitHub account.
4. Press the button to start the upload operation.
5. The app displays the upload status and progress while the operation is running.
6. When the operation is completed, the repository URL is displayed and can be tapped to open it directly in the phone's web browser.

### GitHub Access

The application communicates with GitHub through the **GitHub API**, so it requires a **Personal Access Token** with access to the repositories you want to use.

Create the token through GitHub and enter it in the Token field in the app. When using a **Fine-grained Token**, make sure it has the required permissions for the selected repositories, especially permission to read and write repository contents.

### Creating or Updating a Project

When creating a new Repository, the app creates the repository and then uploads the contents of the selected ZIP file to it.

When updating an existing Repository, the app lets you select the required repository and then uploads the ZIP project contents to update the project files on GitHub.

### Project Link

The default project link is:

`https://github.com/jhftyyyty/GitHub_uploader`

The project link is fixed in the app and is shown in Settings for reference.
