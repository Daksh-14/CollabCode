
const ConfirmDelete = ({onCancel,onDelete,confirmDelete}) => {
    console.log("ConfirmDelete component rendered");
    console.log("ConfirmDelete props:", {onCancel, onDelete, confirmDelete});
    return (
        <div className="fixed inset-0  bg-opacity-50 flex items-center justify-center z-50" >
            <div className="bg-[#2d2d2d] text-white p-6 rounded-xl w-[300px] shadow-lg">
                <h3 className="text-lg font-semibold mb-4">Delete Confirmation</h3>
                <p className="mb-6">
                    Are you sure you want to delete <span className="text-red-400 font-mono">{confirmDelete.path}</span>?
                </p>
                <div className="flex justify-end space-x-3">
                    <button
                        onClick={onCancel}
                        className="px-4 py-2 rounded bg-gray-600 hover:bg-gray-500"
                    >
                        Cancel
                    </button>
                    <button
                        onClick={onDelete}
                        className="px-4 py-2 rounded bg-red-600 hover:bg-red-500"
                    >
                        Delete
                    </button>
                </div>
            </div>
        </div >
    );
}

export default ConfirmDelete;